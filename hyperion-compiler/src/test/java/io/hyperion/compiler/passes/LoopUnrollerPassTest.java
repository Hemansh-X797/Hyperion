package io.hyperion.compiler.passes;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.compiler.backend.spirv.KernelSpirvEmitter;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.IrInterpreter;
import io.hyperion.core.ir.SsaConstructor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class LoopUnrollerPassTest {

    @GpuKernel
    public static float integrateFixed(float x0, float dx) {
        float x = x0;
        for (int i = 0; i < 4; i++) {
            x = x + dx * x;
        }
        return x;
    }

    @GpuKernel
    public static int sumTo(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s = s + i;
        }
        return s;
    }

    @Test
    void unrolledKernelHasOneBlock() throws Exception {
        Method m = LoopUnrollerPassTest.class.getMethod("integrateFixed", float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);
        IrFunction unrolled = LoopUnrollerPass.unroll(fn);
        assertEquals(1, unrolled.blocks.size());
    }

    @Test
    void unrolledKernelMatchesRealExecution() throws Exception {
        Method m = LoopUnrollerPassTest.class.getMethod("integrateFixed", float.class, float.class);
        IrFunction unrolled = DeadCodeEliminationPass.run(LoopUnrollerPass.unroll(SsaConstructor.build(m)));
        for (float x0 = -5f; x0 <= 5f; x0 += 1.7f) {
            for (float dx = -0.2f; dx <= 0.2f; dx += 0.05f) {
                double ir = IrInterpreter.run(unrolled, new double[] {x0, dx});
                double real = integrateFixed(x0, dx);
                assertEquals(real, ir, 1e-3, "mismatch for x0=" + x0 + " dx=" + dx);
            }
        }
    }

    @Test
    void unrolledKernelCompilesToSpirvWhereOriginalDoesNot() throws Exception {
        Method m = LoopUnrollerPassTest.class.getMethod("integrateFixed", float.class, float.class);
        IrFunction original = SsaConstructor.build(m);
        assertThrows(KernelSpirvEmitter.UnsupportedIrShapeException.class, () -> KernelSpirvEmitter.emit(original));

        IrFunction optimized = DeadCodeEliminationPass.run(LoopUnrollerPass.unroll(original));
        byte[] spirv = KernelSpirvEmitter.emit(optimized); // must not throw
        assertTrue(spirv.length > 0);
    }

    @Test
    void refusesDataDependentLoopBound() throws Exception {
        Method m = LoopUnrollerPassTest.class.getMethod("sumTo", int.class);
        IrFunction fn = SsaConstructor.build(m);
        assertThrows(LoopUnrollerPass.UnsupportedLoopShapeException.class, () -> LoopUnrollerPass.unroll(fn));
    }

    @Test
    void deadCodeEliminationRemovesUnusedLoopCounterArithmetic() throws Exception {
        Method m = LoopUnrollerPassTest.class.getMethod("integrateFixed", float.class, float.class);
        IrFunction unrolled = LoopUnrollerPass.unroll(SsaConstructor.build(m));
        IrFunction optimized = DeadCodeEliminationPass.run(unrolled);
        // every remaining owned value in the optimized single block must actually be an F32
        // computation (the loop counter arithmetic, all I32, must be gone)
        for (var v : optimized.blocks.get(0).owned) {
            assertEquals(io.hyperion.core.ir.IrType.F32, v.type, "found leftover non-F32 (likely dead loop-counter) value: " + v.render());
        }
    }
}
