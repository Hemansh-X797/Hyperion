package io.hyperion.compiler.passes;

import io.hyperion.core.ir.*;

import java.util.*;

/**
 * Fully unrolls a simple counted loop whose trip count is known at compile
 * time, turning a 4-block loop (preheader → header → body → exit) into a
 * single straight-line block with the body's arithmetic replicated once
 * per iteration and every loop-carried phi replaced by direct SSA value
 * chaining — a real, general symbolic-substitution unroll, not a
 * re-execution shortcut.
 *
 * <h2>Why this matters beyond "faster code"</h2>
 * Phase 4's {@code KernelSpirvEmitter} only lowers single-block kernels
 * (SPIR-V's structured control flow for {@code OpLoopMerge}/{@code OpPhi}
 * isn't implemented yet). Unrolling a compile-time-bounded loop collapses
 * it to single-block form, which means a kernel that Phase 4 previously
 * *rejected* can now be compiled to SPIR-V and dispatched on a real GPU
 * (Phase 5) — this pass doesn't just optimize, it unlocks a class of
 * kernels for the existing backend.
 *
 * <h2>Scope (honest boundary)</h2>
 * Requires exactly the 4-block shape {@link io.hyperion.core.ir.SsaConstructor}
 * produces for a simple {@code for}/{@code while} loop; a counter phi whose
 * initial value and per-iteration step are both compile-time constants;
 * a header comparison against a compile-time-constant bound using {@code <}
 * or {@code <=}; and an exact (remainder-free) trip count under
 * {@link #MAX_UNROLL_FACTOR}. Nested loops, data-dependent bounds (like
 * {@code sumTo(int n)} from Phase 3 — {@code n} is a parameter, not a
 * constant), and non-unit-comparable conditions are rejected outright
 * rather than partially/incorrectly unrolled.
 */
public final class LoopUnrollerPass {

    public static final int MAX_UNROLL_FACTOR = 64;

    public static final class UnsupportedLoopShapeException extends RuntimeException {
        public UnsupportedLoopShapeException(String message) { super(message); }
    }

    private int nextValueId;

    private LoopUnrollerPass(int startingValueId) {
        this.nextValueId = startingValueId;
    }

    public static IrFunction unroll(IrFunction fn) {
        if (fn.blocks.size() != 4) {
            throw new UnsupportedLoopShapeException(
                    "Loop unroller baseline only handles the exact 4-block preheader/header/body/exit "
                            + "shape; " + fn.name + " has " + fn.blocks.size() + " blocks");
        }
        int maxId = 0;
        for (BasicBlock b : fn.blocks) for (IrValue v : b.owned) maxId = Math.max(maxId, v.id);
        for (IrValue.ParamValue p : fn.params) maxId = Math.max(maxId, p.id);
        return new LoopUnrollerPass(maxId + 1).unrollInternal(fn);
    }

    private IrFunction unrollInternal(IrFunction fn) {
        BasicBlock preheader = fn.blocks.get(0);
        BasicBlock header = fn.blocks.get(1);
        BasicBlock body = fn.blocks.get(2);
        BasicBlock exit = fn.blocks.get(3);

        requireShape(preheader.successors.size() == 1 && preheader.successors.get(0) == header,
                "preheader must jump straight to header");
        requireShape(header.predecessors.size() == 2, "header must have exactly 2 predecessors");
        requireShape(header.predecessors.contains(preheader) && header.predecessors.contains(body),
                "header's predecessors must be exactly {preheader, body}");
        requireShape(body.predecessors.size() == 1 && body.predecessors.get(0) == header,
                "loop body must have header as its only predecessor");
        requireShape(body.successors.size() == 1 && body.successors.get(0) == header,
                "loop body must jump back to header (the back-edge)");
        requireShape(header.terminator instanceof Terminator.CondBranch,
                "header must end in a conditional branch");

        Terminator.CondBranch headerBranch = (Terminator.CondBranch) header.terminator;
        // javac's real encoding (confirmed against actual compiled bytecode) inverts the
        // source condition: `for (i < 4)` compiles to `if (i >= 4) goto exit` with
        // fallthrough to the body — i.e. trueTarget=exit, falseTarget=body, NOT the naive
        // trueTarget=body assumption. Handle both orderings and normalize.
        boolean invertedEncoding;
        if (headerBranch.trueTarget() == body && headerBranch.falseTarget() == exit) {
            invertedEncoding = false;
        } else if (headerBranch.trueTarget() == exit && headerBranch.falseTarget() == body) {
            invertedEncoding = true;
        } else {
            throw new UnsupportedLoopShapeException(
                    "header's branch must take (body, exit) in either direct or inverted form");
        }

        List<IrValue.PhiValue> headerPhis = new ArrayList<>();
        for (IrValue v : header.owned) {
            if (v instanceof IrValue.PhiValue phi && phi.replacement == null) headerPhis.add(phi);
        }

        // Identify the counter phi: initial value and backedge step are both constants.
        IrValue.PhiValue counterPhi = null;
        double initialValue = 0, step = 0;
        for (IrValue.PhiValue phi : headerPhis) {
            IrValue initOperand = phi.operands.get(preheader).resolve();
            IrValue backOperand = phi.operands.get(body).resolve();
            if (!(initOperand instanceof IrValue.ConstValue initConst)) continue;
            if (!(backOperand instanceof IrValue.BinOpValue backBin)) continue;
            if (!backBin.op.equals("+") && !backBin.op.equals("-")) continue;
            boolean phiIsLeft = backBin.left.resolve() == phi;
            boolean phiIsRight = backBin.right.resolve() == phi;
            if (!phiIsLeft && !phiIsRight) continue;
            IrValue otherSide = (phiIsLeft ? backBin.right : backBin.left).resolve();
            if (!(otherSide instanceof IrValue.ConstValue stepConst)) continue;
            counterPhi = phi;
            initialValue = initConst.value;
            step = backBin.op.equals("+") ? stepConst.value : -stepConst.value;
            break;
        }
        if (counterPhi == null) {
            throw new UnsupportedLoopShapeException(
                    "Could not find a counter phi with constant initial value and constant step in " + fn.name);
        }
        if (step == 0) {
            throw new UnsupportedLoopShapeException("Loop counter step is zero — infinite loop, refusing to unroll");
        }

        // Header's branch must compare the counter phi against a compile-time constant bound.
        IrValue branchLeft = headerBranch.left().resolve();
        IrValue branchRight = headerBranch.right().resolve();
        double bound;
        if (branchLeft == counterPhi && branchRight instanceof IrValue.ConstValue bc) {
            bound = bc.value;
        } else if (branchRight == counterPhi && branchLeft instanceof IrValue.ConstValue bc) {
            bound = bc.value;
        } else {
            throw new UnsupportedLoopShapeException(
                    "Header comparison in " + fn.name + " does not compare the counter phi against a "
                            + "compile-time constant bound — likely a data-dependent loop (e.g. sumTo(int n)), "
                            + "which this baseline correctly refuses to unroll");
        }
        String cmp = headerBranch.cmpOp();
        String continueCmp = invertedEncoding ? negate(cmp) : cmp;
        if (!(branchLeft == counterPhi && continueCmp.equals("<"))) {
            throw new UnsupportedLoopShapeException(
                    "Only 'counter < bound' style loop conditions are supported by this baseline, got "
                            + "continue-condition: " + branchLeft + " " + continueCmp + " " + branchRight
                            + " (raw branch was: " + branchLeft + " " + cmp + " " + branchRight
                            + ", inverted=" + invertedEncoding + ")");
        }

        double tripCountExact = (bound - initialValue) / step;
        int tripCount = (int) Math.round(tripCountExact);
        if (Math.abs(tripCountExact - tripCount) > 1e-9 || tripCount < 0) {
            throw new UnsupportedLoopShapeException(
                    "Trip count (" + tripCountExact + ") is not a non-negative integer — refusing to unroll "
                            + "a loop with a fractional or negative iteration count");
        }
        if (tripCount > MAX_UNROLL_FACTOR) {
            throw new UnsupportedLoopShapeException(
                    "Trip count " + tripCount + " exceeds MAX_UNROLL_FACTOR=" + MAX_UNROLL_FACTOR
                            + " — refusing to unroll to avoid code-size explosion");
        }

        // ---- Perform the unroll: symbolic replay of the body, `tripCount` times ----
        BasicBlock newBlock = new BasicBlock(0);
        Map<IrValue, IrValue> currentPhiValues = new HashMap<>();
        for (IrValue.PhiValue phi : headerPhis) {
            currentPhiValues.put(phi, phi.operands.get(preheader).resolve());
        }

        for (int iter = 0; iter < tripCount; iter++) {
            Map<IrValue, IrValue> substitution = new IdentityHashMap<>(currentPhiValues);
            for (IrValue v : body.owned) {
                IrValue cloned = cloneWithSubstitution(v, substitution, newBlock);
                substitution.put(v, cloned);
            }
            Map<IrValue, IrValue> nextPhiValues = new HashMap<>();
            for (IrValue.PhiValue phi : headerPhis) {
                IrValue backedgeExpr = phi.operands.get(body).resolve();
                nextPhiValues.put(phi, resolveSubstituted(backedgeExpr, substitution));
            }
            currentPhiValues = nextPhiValues;
        }

        // Exit block's own instructions (if any) + terminator, with header phis substituted
        // by their final post-loop values.
        Map<IrValue, IrValue> exitSubstitution = new IdentityHashMap<>(currentPhiValues);
        for (IrValue v : exit.owned) {
            IrValue cloned = cloneWithSubstitution(v, exitSubstitution, newBlock);
            exitSubstitution.put(v, cloned);
        }
        if (!(exit.terminator instanceof Terminator.Return exitReturn)) {
            throw new UnsupportedLoopShapeException("Loop exit block must end in a return for this baseline");
        }
        IrValue finalReturnValue = resolveSubstituted(exitReturn.value().resolve(), exitSubstitution);
        newBlock.terminator = new Terminator.Return(finalReturnValue);

        return new IrFunction(fn.name, fn.params, fn.returnType, List.of(newBlock), newBlock);
    }

    private IrValue resolveSubstituted(IrValue v, Map<IrValue, IrValue> substitution) {
        IrValue resolved = v.resolve();
        IrValue sub = substitution.get(resolved);
        return sub != null ? sub : resolved;
    }

    private IrValue cloneWithSubstitution(IrValue v, Map<IrValue, IrValue> substitution, BasicBlock target) {
        IrValue resolved = v.resolve();
        IrValue existing = substitution.get(resolved);
        if (existing != null) return existing;

        if (resolved instanceof IrValue.ParamValue || resolved instanceof IrValue.ConstValue) {
            return resolved; // no per-iteration identity; safe to share across all unrolled copies
        }
        if (resolved instanceof IrValue.BinOpValue bin) {
            IrValue left = cloneWithSubstitution(bin.left, substitution, target);
            IrValue right = cloneWithSubstitution(bin.right, substitution, target);
            IrValue.BinOpValue cloned = new IrValue.BinOpValue(nextValueId++, bin.type, bin.op, left, right);
            target.owned.add(cloned);
            substitution.put(resolved, cloned);
            return cloned;
        }
        throw new UnsupportedLoopShapeException(
                "Loop body references a value this baseline can't clone: " + resolved.getClass().getSimpleName()
                        + " (nested loops / externally-scoped phis are not supported)");
    }

    private static String negate(String cmp) {
        return switch (cmp) {
            case "<" -> ">=";
            case "<=" -> ">";
            case ">" -> "<=";
            case ">=" -> "<";
            case "==" -> "!=";
            case "!=" -> "==";
            default -> throw new UnsupportedLoopShapeException("Unknown comparison operator: " + cmp);
        };
    }

    private static void requireShape(boolean condition, String message) {
        if (!condition) {
            throw new UnsupportedLoopShapeException("Unsupported loop shape: " + message);
        }
    }
}
