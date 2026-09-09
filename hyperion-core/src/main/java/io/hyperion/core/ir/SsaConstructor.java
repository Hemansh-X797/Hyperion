package io.hyperion.core.ir;

import io.hyperion.core.ir.IrValue.BinOpValue;
import io.hyperion.core.ir.IrValue.ConstValue;
import io.hyperion.core.ir.IrValue.ParamValue;
import io.hyperion.core.ir.IrValue.PhiValue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Builds Hyperion SSA IR directly from real JVM bytecode via
 * {@code java.lang.classfile}, lifting Phase 2's "no branches/loops"
 * restriction with a genuine control-flow graph and Braun et al.'s
 * SSA construction algorithm (2013) — no dominance-frontier precomputation
 * needed, just "incomplete phis" for not-yet-sealed blocks and trivial-phi
 * elimination, driven by processing basic blocks in bytecode program order.
 *
 * <h2>Two-phase construction</h2>
 * <ol>
 *   <li><b>Structural pass</b> — partition bytecode into basic blocks by
 *   the standard leader algorithm, resolve every branch target {@link Label}
 *   to its block, and link predecessor/successor edges. No SSA values are
 *   created yet.</li>
 *   <li><b>Value pass</b> — walk blocks in bytecode program order,
 *   symbolically executing each block's local operand stack. Local-variable
 *   reads/writes go through {@code readVariable}/{@code writeVariable}.
 *   Immediately after a block finishes, its successors' "predecessor done"
 *   counters are incremented; a successor is sealed (its incomplete phis
 *   finalized) exactly when every one of its predecessors has finished.
 *   This is what correctly resolves loop-carried variables: a loop header
 *   reached via a not-yet-processed back-edge stays unsealed while its
 *   forward predecessors run, so a read inside the loop body gets a
 *   placeholder phi first and the real back-edge operand filled in once the
 *   body itself finishes and the header's last predecessor count lands.</li>
 * </ol>
 *
 * <h2>Scope</h2>
 * Static methods, primitive locals/params, straight-line arithmetic plus
 * {@code if}/{@code while}/{@code for} control flow compiled by javac in the
 * ordinary way. Operand stack must be empty at every block boundary (true
 * for real if/loop statements; false for stack-spanning constructs like the
 * ternary operator, which are rejected) — verified, not assumed.
 */
public final class SsaConstructor {

    public static final class UnsupportedControlFlowException extends RuntimeException {
        public UnsupportedControlFlowException(String message) { super(message); }
    }

    private int nextValueId = 0;
    private int nextBlockId = 0;

    // slot -> (block -> current value) : the heart of Braun et al.'s algorithm
    private final Map<Integer, Map<BasicBlock, IrValue>> currentDef = new HashMap<>();

    // element sublists per block index, populated by partitionIntoBlocks, consumed by the rest of construction
    private List<List<CodeElement>> blockRanges;

    private SsaConstructor() {}

    public static IrFunction build(Method method) {
        return new SsaConstructor().buildInternal(method);
    }

    private IrFunction buildInternal(Method method) {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new UnsupportedControlFlowException("Only static kernel methods are supported, got: " + method);
        }

        ClassModel classModel = parseDeclaringClass(method);
        MethodModel methodModel = findMethodModel(classModel, method);
        CodeModel codeModel = methodModel.code().orElseThrow(() ->
                new UnsupportedControlFlowException("No Code attribute for " + method));

        List<CodeElement> elements = new ArrayList<>();
        for (CodeElement e : codeModel) elements.add(e);

        // ---- Phase A: structural CFG ----
        List<BasicBlock> blocks = partitionIntoBlocks(elements);
        Map<Label, BasicBlock> labelToBlock = mapLabelsToBlocks(blocks);
        linkTerminatorEdges(blocks, labelToBlock);

        for (BasicBlock b : blocks) {
            if (b.predecessors.isEmpty()) {
                b.sealed = true; // entry, or genuinely unreachable — either way, no predecessors to wait for
            }
        }

        // ---- Phase B: SSA value construction, single pass in program order ----
        Class<?>[] paramTypes = method.getParameterTypes();
        List<ParamValue> params = new ArrayList<>();
        int slot = 0;
        BasicBlock entry = blocks.get(0);
        for (int i = 0; i < paramTypes.length; i++) {
            TypeKind k = TypeKind.from(paramTypes[i]);
            ParamValue p = new ParamValue(nextValueId++, IrType.from(k), i, "p" + i);
            params.add(p);
            writeVariable(slot, entry, p);
            slot += k.slotSize();
        }

        for (BasicBlock block : blocks) {
            processBlockInstructions(block);
            finishBlock(block);
        }

        return new IrFunction(method.getName(), params, IrType.from(TypeKind.from(method.getReturnType())), blocks, entry);
    }

    // =================================================================
    // Phase A: structural CFG construction
    // =================================================================

    private List<BasicBlock> partitionIntoBlocks(List<CodeElement> elements) {
        Set<Integer> leaderIndices = new TreeSet<>();
        leaderIndices.add(0);
        for (int i = 0; i < elements.size(); i++) {
            CodeElement e = elements.get(i);
            if (e instanceof BranchInstruction || e instanceof ReturnInstruction) {
                if (i + 1 < elements.size()) leaderIndices.add(i + 1);
            }
            if (e instanceof LabelTarget) {
                leaderIndices.add(i); // the LabelTarget pseudo-element itself starts a new block
            }
        }

        List<Integer> sorted = new ArrayList<>(leaderIndices);
        List<BasicBlock> blocks = new ArrayList<>();
        for (int bi = 0; bi < sorted.size(); bi++) {
            blocks.add(new BasicBlock(nextBlockId++));
        }
        this.blockRanges = new ArrayList<>();
        for (int bi = 0; bi < sorted.size(); bi++) {
            int start = sorted.get(bi);
            int end = (bi + 1 < sorted.size()) ? sorted.get(bi + 1) : elements.size();
            blockRanges.add(elements.subList(start, end));
        }
        return blocks;
    }

    private Map<Label, BasicBlock> mapLabelsToBlocks(List<BasicBlock> blocks) {
        Map<Label, BasicBlock> map = new HashMap<>();
        for (int bi = 0; bi < blocks.size(); bi++) {
            for (CodeElement e : blockRanges.get(bi)) {
                if (e instanceof LabelTarget lt) {
                    map.put(lt.label(), blocks.get(bi));
                }
            }
        }
        return map;
    }

    private void linkTerminatorEdges(List<BasicBlock> blocks, Map<Label, BasicBlock> labelToBlock) {
        for (int bi = 0; bi < blocks.size(); bi++) {
            BasicBlock block = blocks.get(bi);
            BasicBlock fallthrough = (bi + 1 < blocks.size()) ? blocks.get(bi + 1) : null;

            Instruction lastInstr = null;
            for (CodeElement e : blockRanges.get(bi)) {
                if (e instanceof Instruction instr) lastInstr = instr;
            }

            if (lastInstr instanceof ReturnInstruction) {
                // terminal, no successors
            } else if (lastInstr instanceof BranchInstruction br) {
                BasicBlock target = labelToBlock.get(br.target());
                if (target == null) {
                    throw new UnsupportedControlFlowException("Branch target label not found in method (exception handler edge?)");
                }
                addEdge(block, target);
                if (br.opcode() != Opcode.GOTO && br.opcode() != Opcode.GOTO_W) {
                    if (fallthrough == null) {
                        throw new UnsupportedControlFlowException("Conditional branch falls off the end of the method");
                    }
                    addEdge(block, fallthrough);
                }
            } else {
                if (fallthrough != null) {
                    addEdge(block, fallthrough);
                }
            }
        }
    }

    private void addEdge(BasicBlock from, BasicBlock to) {
        from.successors.add(to);
        to.predecessors.add(from);
    }

    // =================================================================
    // Phase B: SSA value construction (Braun et al.)
    // =================================================================

    private void processBlockInstructions(BasicBlock block) {
        int blockIndex = block.id;
        Deque<IrValue> stack = new ArrayDeque<>();
        String pendingFloatCmp = null;
        IrValue pendingCmpLeft = null, pendingCmpRight = null;

        for (CodeElement e : blockRanges.get(blockIndex)) {
            if (e instanceof LoadInstruction load) {
                IrValue v = readVariable(load.slot(), block);
                stack.push(v);

            } else if (e instanceof ConstantInstruction ci) {
                double val = toDouble(ci.constantValue());
                IrValue c = new ConstValue(nextValueId++, IrType.from(ci.typeKind()), val);
                block.owned.add(c);
                stack.push(c);

            } else if (e instanceof StoreInstruction st) {
                IrValue v = stack.pop();
                writeVariable(st.slot(), block, v);

            } else if (e instanceof IncrementInstruction inc) {
                IrValue current = readVariable(inc.slot(), block);
                IrValue delta = new ConstValue(nextValueId++, IrType.I32, inc.constant());
                block.owned.add(delta);
                IrValue sum = new BinOpValue(nextValueId++, IrType.I32, "+", current, delta);
                block.owned.add(sum);
                writeVariable(inc.slot(), block, sum);

            } else if (e instanceof OperatorInstruction op) {
                String opName = op.opcode().name();
                if (opName.startsWith("FCMP") || opName.startsWith("DCMP") || opName.startsWith("LCMP")) {
                    IrValue right = stack.pop();
                    IrValue left = stack.pop();
                    pendingCmpLeft = left;
                    pendingCmpRight = right;
                    pendingFloatCmp = "cmp";
                } else {
                    String symbol = operatorSymbol(op.opcode());
                    IrValue right = stack.pop();
                    IrValue left = stack.pop();
                    IrValue result = new BinOpValue(nextValueId++, IrType.from(op.typeKind()), symbol, left, right);
                    block.owned.add(result);
                    stack.push(result);
                }

            } else if (e instanceof BranchInstruction br) {
                if (br.opcode() == Opcode.GOTO || br.opcode() == Opcode.GOTO_W) {
                    block.terminator = new Terminator.Jump(block.successors.get(0));
                } else {
                    setConditionalTerminator(block, br.opcode(), pendingFloatCmp != null, pendingCmpLeft, pendingCmpRight, stack);
                }

            } else if (e instanceof ReturnInstruction) {
                IrValue v = stack.isEmpty() ? null : stack.pop();
                if (v == null) {
                    throw new UnsupportedControlFlowException("void returns are not supported in kernels");
                }
                block.terminator = new Terminator.Return(v);

            } else if (e instanceof Instruction instr && !(e instanceof LabelTarget)) {
                throw new UnsupportedControlFlowException(
                        "Unsupported instruction " + instr.opcode() + " in kernel body "
                                + "(Phase 3 baseline: no field/array access, casts, or calls yet)");
            }
        }

        if (block.terminator == null) {
            if (!block.successors.isEmpty()) {
                block.terminator = new Terminator.Jump(block.successors.get(0));
            } else {
                throw new UnsupportedControlFlowException("Block " + block + " has no terminator and no successor");
            }
        }
        if (!stack.isEmpty()) {
            throw new UnsupportedControlFlowException(
                    "Operand stack not empty (" + stack.size() + " value(s)) at end of block " + block
                            + " — stack-spanning control flow (e.g. the ?: ternary operator) is not supported; "
                            + "use an if-statement instead");
        }
    }

    private void setConditionalTerminator(BasicBlock block, Opcode opcode, boolean hasPendingCmp,
                                           IrValue pendingLeft, IrValue pendingRight, Deque<IrValue> stack) {
        String name = opcode.name();
        BasicBlock trueTarget = block.successors.get(0); // addEdge order: branch target added first
        BasicBlock falseTarget = block.successors.get(1); // then fallthrough
        String cmpOp;
        IrValue left, right;

        if (name.startsWith("IF_ICMP")) {
            right = stack.pop();
            left = stack.pop();
            cmpOp = relFromSuffix(name.substring("IF_ICMP".length()));
        } else if (hasPendingCmp) {
            left = pendingLeft;
            right = pendingRight;
            cmpOp = relFromSuffix(name.substring("IF".length()));
        } else {
            left = stack.pop();
            ConstValue zero = new ConstValue(nextValueId++, IrType.I32, 0);
            block.owned.add(zero);
            right = zero;
            cmpOp = relFromSuffix(name.substring("IF".length()));
        }
        block.terminator = new Terminator.CondBranch(cmpOp, left, right, trueTarget, falseTarget);
    }

    private static String relFromSuffix(String suffix) {
        return switch (suffix) {
            case "LT" -> "<";
            case "LE" -> "<=";
            case "GT" -> ">";
            case "GE" -> ">=";
            case "EQ" -> "==";
            case "NE" -> "!=";
            default -> throw new UnsupportedControlFlowException("Unsupported comparison suffix: " + suffix);
        };
    }

    private void finishBlock(BasicBlock block) {
        for (BasicBlock succ : block.successors) {
            succ.processedPredecessorCount++;
            if (!succ.sealed && succ.processedPredecessorCount == succ.predecessors.size()) {
                seal(succ);
            }
        }
    }

    // ---- Braun et al. core: readVariable / writeVariable / seal / phi handling ----

    private void writeVariable(int slot, BasicBlock block, IrValue value) {
        currentDef.computeIfAbsent(slot, k -> new HashMap<>()).put(block, value);
    }

    private IrValue readVariable(int slot, BasicBlock block) {
        Map<BasicBlock, IrValue> defs = currentDef.get(slot);
        if (defs != null) {
            IrValue local = defs.get(block);
            if (local != null) return local;
        }
        return readVariableRecursive(slot, block);
    }

    private IrValue readVariableRecursive(int slot, BasicBlock block) {
        IrValue value;
        if (!block.sealed) {
            PhiValue phi = new PhiValue(nextValueId++, IrType.I32, block);
            block.incompletePhis.put(slot, phi);
            block.owned.add(phi);
            value = phi;
        } else if (block.predecessors.size() == 1) {
            value = readVariable(slot, block.predecessors.get(0));
        } else {
            PhiValue phi = new PhiValue(nextValueId++, IrType.I32, block);
            block.owned.add(phi);
            writeVariable(slot, block, phi); // break cycles before recursing into predecessors
            value = addPhiOperands(slot, phi);
        }
        writeVariable(slot, block, value);
        return value;
    }

    private IrValue addPhiOperands(int slot, PhiValue phi) {
        for (BasicBlock pred : phi.block.predecessors) {
            IrValue operand = readVariable(slot, pred);
            phi.operands.put(pred, operand);
        }
        return tryRemoveTrivialPhi(phi);
    }

    private IrValue tryRemoveTrivialPhi(PhiValue phi) {
        IrValue same = null;
        for (IrValue op : phi.operands.values()) {
            IrValue resolved = op.resolve();
            if (resolved == phi || resolved == same) continue;
            if (same != null) {
                return phi;
            }
            same = resolved;
        }
        if (same == null) {
            return phi;
        }
        phi.replacement = same;
        return same;
    }

    private void seal(BasicBlock block) {
        block.sealed = true;
        for (var entry : new ArrayList<>(block.incompletePhis.entrySet())) {
            addPhiOperands(entry.getKey(), entry.getValue());
        }
        block.incompletePhis.clear();
    }

    // =================================================================
    // shared helpers
    // =================================================================

    private static double toDouble(Object constantValue) {
        return switch (constantValue) {
            case Integer i -> i.doubleValue();
            case Long l -> l.doubleValue();
            case Float f -> f.doubleValue();
            case Double d -> d;
            default -> throw new UnsupportedControlFlowException("Unsupported constant type: " + constantValue);
        };
    }

    private static String operatorSymbol(Opcode opcode) {
        String name = opcode.name();
        if (name.endsWith("ADD")) return "+";
        if (name.endsWith("SUB")) return "-";
        if (name.endsWith("MUL")) return "*";
        if (name.endsWith("DIV")) return "/";
        throw new UnsupportedControlFlowException("Unsupported operator opcode: " + opcode);
    }

    private static ClassModel parseDeclaringClass(Method method) {
        Class<?> owner = method.getDeclaringClass();
        String resourceName = owner.getSimpleName() + ".class";
        try (InputStream in = owner.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException("Could not locate class file for " + owner));
            }
            return ClassFile.of().parse(in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MethodModel findMethodModel(ClassModel classModel, Method method) {
        MethodTypeDesc expected = MethodTypeDesc.ofDescriptor(
                MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString());
        for (MethodModel m : classModel.methods()) {
            if (m.methodName().stringValue().equals(method.getName()) && m.methodTypeSymbol().equals(expected)) {
                return m;
            }
        }
        throw new UnsupportedControlFlowException("Could not find MethodModel for " + method);
    }
}
