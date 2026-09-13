package io.hyperion.compiler.backend.vector;

import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.IrType;
import io.hyperion.core.ir.IrValue;
import io.hyperion.core.ir.Terminator;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorSpecies;

/**
 * Executes a straight-line Hyperion kernel over a batch of independent
 * inputs using real hardware SIMD via {@code jdk.incubator.vector} — the
 * CPU fallback path for when no GPU is present (Phase 5's dispatch model
 * is one invocation per {@code dispatch()} call; this is the "N elements,
 * data-parallel, on the CPU" counterpart).
 *
 * <h2>How it works</h2>
 * The kernel's SSA graph is walked once per {@link VectorSpecies}-width
 * chunk of the batch, exactly like {@link io.hyperion.core.ir.IrInterpreter}
 * but with every value being a {@link FloatVector} (one SIMD register's
 * worth of lanes) instead of a scalar {@code double} — so a single
 * {@code OpFMul}-equivalent Java call computes {@code species.length()}
 * (16 on AVX-512 hardware) kernel outputs at once. A scalar tail loop
 * handles the remainder when the batch size isn't a multiple of the
 * species width.
 *
 * <h2>Scope</h2>
 * Straight-line (single-block), {@code F32}-only kernels — the same
 * restriction {@code KernelSpirvEmitter} has, for the same reason: real
 * control flow needs real handling (predicated lane masking for
 * SIMD, not just "unroll it" — a different, deferred problem from Phase 6's
 * loop unrolling, which produces exactly this straight-line shape as one
 * of its target consumers).
 */
public final class VectorKernelExecutor {

    public static final class UnsupportedIrShapeException extends RuntimeException {
        public UnsupportedIrShapeException(String message) { super(message); }
    }

    private static final VectorSpecies<Float> SPECIES = FloatVector.SPECIES_PREFERRED;

    private VectorKernelExecutor() {}

    public static int laneWidth() {
        return SPECIES.length();
    }

    /**
     * Runs {@code fn} once per element across {@code inputs} (one array per
     * kernel parameter, all the same length N), returning an N-length
     * result array. Every element is independent — this is the same
     * "single invocation, many times" model as GPU dispatch, just executed
     * with CPU SIMD registers instead of GPU work-items.
     */
    public static float[] executeBatch(IrFunction fn, float[][] inputs) {
        validateShape(fn);
        int paramCount = fn.params.size();
        if (inputs.length != paramCount) {
            throw new IllegalArgumentException("Expected " + paramCount + " input arrays, got " + inputs.length);
        }
        int n = inputs.length == 0 ? 0 : inputs[0].length;
        for (float[] arr : inputs) {
            if (arr.length != n) throw new IllegalArgumentException("All input arrays must have the same length");
        }

        float[] output = new float[n];
        int upperBound = SPECIES.loopBound(n);

        var block = fn.blocks.get(0);
        Terminator.Return ret = (Terminator.Return) block.terminator;

        int maxId = 0;
        for (IrValue.ParamValue p : fn.params) maxId = Math.max(maxId, p.id);
        for (IrValue v : block.owned) maxId = Math.max(maxId, v.id);
        FloatVector[] vecRegs = new FloatVector[maxId + 1]; // register file, reused across all chunks — no per-chunk allocation

        int i = 0;
        for (; i < upperBound; i += SPECIES.length()) {
            for (IrValue.ParamValue p : fn.params) {
                vecRegs[p.id] = FloatVector.fromArray(SPECIES, inputs[p.paramIndex], i);
            }
            for (IrValue v : block.owned) {
                evalVector(v, vecRegs);
            }
            vecRegs[ret.value().resolve().id].intoArray(output, i);
        }

        // Scalar tail for the remainder (n - upperBound elements, always < lane width).
        float[] scalarRegs = new float[maxId + 1];
        for (; i < n; i++) {
            for (IrValue.ParamValue p : fn.params) {
                scalarRegs[p.id] = inputs[p.paramIndex][i];
            }
            for (IrValue v : block.owned) {
                evalScalarTail(v, scalarRegs);
            }
            output[i] = scalarRegs[ret.value().resolve().id];
        }

        return output;
    }

    private static void evalVector(IrValue v, FloatVector[] regs) {
        if (v instanceof IrValue.ParamValue) {
            return; // already seeded
        }
        if (v instanceof IrValue.ConstValue c) {
            regs[v.id] = FloatVector.broadcast(SPECIES, (float) c.value);
            return;
        }
        if (v instanceof IrValue.BinOpValue bin) {
            FloatVector left = regs[bin.left.resolve().id];
            FloatVector right = regs[bin.right.resolve().id];
            VectorOperators.Binary op = switch (bin.op) {
                case "+" -> VectorOperators.ADD;
                case "-" -> VectorOperators.SUB;
                case "*" -> VectorOperators.MUL;
                case "/" -> VectorOperators.DIV;
                default -> throw new UnsupportedIrShapeException("Unknown op: " + bin.op);
            };
            regs[v.id] = left.lanewise(op, right);
            return;
        }
        throw new UnsupportedIrShapeException("Cannot vectorize value kind: " + v.getClass().getSimpleName());
    }

    private static void evalScalarTail(IrValue v, float[] regs) {
        if (v instanceof IrValue.ParamValue) {
            return;
        }
        if (v instanceof IrValue.ConstValue c) {
            regs[v.id] = (float) c.value;
            return;
        }
        if (v instanceof IrValue.BinOpValue bin) {
            float left = regs[bin.left.resolve().id];
            float right = regs[bin.right.resolve().id];
            float result = switch (bin.op) {
                case "+" -> left + right;
                case "-" -> left - right;
                case "*" -> left * right;
                case "/" -> left / right;
                default -> throw new UnsupportedIrShapeException("Unknown op: " + bin.op);
            };
            regs[v.id] = result;
            return;
        }
        throw new UnsupportedIrShapeException("Cannot evaluate value kind: " + v.getClass().getSimpleName());
    }

    private static void validateShape(IrFunction fn) {
        if (fn.blocks.size() != 1) {
            throw new UnsupportedIrShapeException(
                    "VectorKernelExecutor only supports straight-line (single-block) kernels; "
                            + fn.name + " has " + fn.blocks.size() + " blocks — unroll first (Phase 6) "
                            + "if the trip count is a compile-time constant.");
        }
        for (IrValue.ParamValue p : fn.params) {
            if (p.type != IrType.F32) {
                throw new UnsupportedIrShapeException("Only F32 kernels are supported, got param type " + p.type);
            }
        }
        if (fn.returnType != IrType.F32) {
            throw new UnsupportedIrShapeException("Only F32 return type is supported, got " + fn.returnType);
        }
        if (!(fn.blocks.get(0).terminator instanceof Terminator.Return)) {
            throw new UnsupportedIrShapeException("Single-block kernel must end in a return");
        }
    }
}
