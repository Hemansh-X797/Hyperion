package io.hyperion.core.reflection;

import io.hyperion.core.reflection.ast.Expr;
import io.hyperion.core.reflection.ast.KernelAst;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.*;
import java.lang.classfile.instruction.ConstantInstruction;
import java.lang.classfile.instruction.LoadInstruction;
import java.lang.classfile.instruction.OperatorInstruction;
import java.lang.classfile.instruction.ReturnInstruction;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Extracts a Hyperion {@link KernelAst} directly from a method's real JVM
 * bytecode, via {@code java.lang.classfile} (JEP 484, finalized JDK 24) —
 * the stock-JDK alternative to Project Babylon's {@code java.lang.reflect
 * .code}, chosen so this project's toolchain stays on a shipped OpenJDK
 * (see README's Phase 2 toolchain note).
 *
 * <h2>Extraction strategy</h2>
 * Bytecode is a stack machine, not a tree; we reconstruct the expression
 * tree by symbolically executing that stack: each instruction either
 * pushes a new {@link Expr} node (loads, constants) or pops operand nodes
 * and pushes a combined node (binary operators), mirroring exactly what
 * the real JVM interpreter does with values — except our "values" are
 * unevaluated expression trees instead of numbers.
 *
 * <h2>Phase 2 baseline scope</h2>
 * Supports {@code static} methods with primitive (int/long/float/double)
 * parameters, a single primitive return, and a body that is pure
 * straight-line arithmetic (+ - * /) with no branches, loops, casts, or
 * calls. Anything outside that shape throws {@link UnsupportedKernelShapeException}
 * with a specific reason — this is a deliberate, honest boundary: control
 * flow needs a real CFG, which is Phase 3's job, not a hack bolted onto
 * Phase 2's stack simulation.
 */
public final class AstExtractor {

    private AstExtractor() {}

    public static final class UnsupportedKernelShapeException extends RuntimeException {
        public UnsupportedKernelShapeException(String message) {
            super(message);
        }
    }

    public static KernelAst extract(Method method) {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new UnsupportedKernelShapeException(
                    "Phase 2 baseline only supports static kernel methods, got instance method: " + method);
        }

        ClassModel classModel = parseDeclaringClass(method);
        MethodModel methodModel = findMethodModel(classModel, method);
        CodeModel codeModel = methodModel.code().orElseThrow(() ->
                new UnsupportedKernelShapeException("Method has no Code attribute (abstract/native?): " + method));

        List<KernelAst.ParamInfo> paramInfos = new ArrayList<>();
        int[] slotOfParam = new int[method.getParameterCount()];
        Class<?>[] paramTypes = method.getParameterTypes();
        int slot = 0;
        for (int i = 0; i < paramTypes.length; i++) {
            TypeKind k = TypeKind.from(paramTypes[i]);
            slotOfParam[i] = slot;
            paramInfos.add(new KernelAst.ParamInfo("p" + i, k));
            slot += k.slotSize();
        }

        Deque<Expr> stack = new ArrayDeque<>();
        Expr result = null;

        for (CodeElement element : codeModel) {
            if (element instanceof LoadInstruction load) {
                int paramIndex = paramIndexForSlot(slotOfParam, load.slot());
                if (paramIndex < 0) {
                    throw new UnsupportedKernelShapeException(
                            "Load from local variable slot " + load.slot()
                                    + " does not correspond to a method parameter — local variables "
                                    + "(intermediate computed values stored with a name) aren't supported "
                                    + "by the Phase 2 baseline yet.");
                }
                stack.push(new Expr.Param(paramIndex, paramInfos.get(paramIndex).name(), load.typeKind()));

            } else if (element instanceof ConstantInstruction constInstr) {
                double value = toDouble(constInstr.constantValue());
                stack.push(new Expr.Const(value, constInstr.typeKind()));

            } else if (element instanceof OperatorInstruction op) {
                String symbol = operatorSymbol(op.opcode());
                Expr right = requirePop(stack, op.opcode());
                Expr left = requirePop(stack, op.opcode());
                stack.push(new Expr.BinaryOp(symbol, left, right, op.typeKind()));

            } else if (element instanceof ReturnInstruction ret) {
                if (ret.typeKind() == TypeKind.VOID) {
                    throw new UnsupportedKernelShapeException("void kernels are not supported — a kernel must return a value");
                }
                if (stack.isEmpty()) {
                    throw new UnsupportedKernelShapeException("return instruction found with an empty operand stack");
                }
                result = stack.pop();
                // A well-formed straight-line kernel returns immediately after computing its
                // result; a real function could have multiple return points under branches,
                // but branches are already rejected above/below, so this is always the last one.

            } else if (element instanceof Instruction instr) {
                throw new UnsupportedKernelShapeException(
                        "Unsupported bytecode instruction " + instr.opcode()
                                + " in " + method + " — the Phase 2 baseline only supports load/constant/"
                                + "arithmetic-operator/return instructions (no branches, loops, field/array "
                                + "access, casts, or calls yet; those arrive with Phase 3's CFG+SSA lowering).");
            }
            // non-Instruction CodeElements (line numbers, local-variable-table debug info,
            // stack map frames) carry no runtime semantics and are silently skipped.
        }

        if (result == null) {
            throw new UnsupportedKernelShapeException("No return instruction found in " + method);
        }
        if (!stack.isEmpty()) {
            throw new UnsupportedKernelShapeException(
                    "Operand stack not empty after return (" + stack.size() + " leftover value(s)) — "
                            + "unreachable/dead code after return is not supported");
        }

        return new KernelAst(method.getName(), paramInfos, TypeKind.from(method.getReturnType()), result);
    }

    private static int paramIndexForSlot(int[] slotOfParam, int slot) {
        for (int i = 0; i < slotOfParam.length; i++) {
            if (slotOfParam[i] == slot) return i;
        }
        return -1;
    }

    private static Expr requirePop(Deque<Expr> stack, Opcode opcode) {
        if (stack.isEmpty()) {
            throw new UnsupportedKernelShapeException(
                    "Operand stack underflow decoding " + opcode + " — malformed or unsupported bytecode shape");
        }
        return stack.pop();
    }

    private static double toDouble(Object constantValue) {
        return switch (constantValue) {
            case Integer i -> i.doubleValue();
            case Long l -> l.doubleValue();
            case Float f -> f.doubleValue();
            case Double d -> d;
            default -> throw new UnsupportedKernelShapeException(
                    "Unsupported constant type in kernel body: " + constantValue.getClass()
                            + " (value=" + constantValue + ") — only numeric constants are supported");
        };
    }

    private static String operatorSymbol(Opcode opcode) {
        String name = opcode.name();
        if (name.endsWith("ADD")) return "+";
        if (name.endsWith("SUB")) return "-";
        if (name.endsWith("MUL")) return "*";
        if (name.endsWith("DIV")) return "/";
        throw new UnsupportedKernelShapeException(
                "Unsupported operator opcode " + opcode + " — only add/sub/mul/div are supported "
                        + "in the Phase 2 baseline (no rem/neg/shift/bitwise yet)");
    }

    private static ClassModel parseDeclaringClass(Method method) {
        Class<?> owner = method.getDeclaringClass();
        String resourceName = owner.getSimpleName() + ".class";
        // Nested/inner classes need the binary name's tail after the last '$', not the
        // simple name reported for anonymous classes — not a concern for our top-level
        // kernel-holder classes, called out here for future maintainers.
        try (InputStream in = owner.getResourceAsStream(resourceName)) {
            if (in == null) {
                throw new UncheckedIOException(new IOException(
                        "Could not locate class file resource " + resourceName + " for " + owner
                                + " on its classloader — is it loaded from a plain classpath directory or jar?"));
            }
            byte[] bytes = in.readAllBytes();
            return ClassFile.of().parse(bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static MethodModel findMethodModel(ClassModel classModel, Method method) {
        MethodTypeDesc expectedDescriptor = MethodTypeDesc.ofDescriptor(
                MethodType.methodType(method.getReturnType(), method.getParameterTypes()).descriptorString());

        List<MethodModel> matches = new ArrayList<>();
        for (MethodModel m : classModel.methods()) {
            if (m.methodName().stringValue().equals(method.getName())
                    && m.methodTypeSymbol().equals(expectedDescriptor)) {
                matches.add(m);
            }
        }
        if (matches.isEmpty()) {
            throw new UnsupportedKernelShapeException(
                    "Could not find matching MethodModel for " + method + " in its own class file "
                            + "(name+descriptor mismatch — this should not happen for a simple static method)");
        }
        if (matches.size() > 1) {
            throw new UnsupportedKernelShapeException(
                    "Ambiguous overload resolution for " + method + " — multiple methods with the same "
                            + "name+descriptor found (should be impossible in valid bytecode)");
        }
        return matches.get(0);
    }
}
