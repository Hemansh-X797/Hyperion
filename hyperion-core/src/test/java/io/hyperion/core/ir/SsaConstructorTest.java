package io.hyperion.core.ir;

import io.hyperion.api.annotation.GpuKernel;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class SsaConstructorTest {

    @GpuKernel
    public static float clampedRelu(float x) {
        if (x < 0f) return 0f;
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
    void branchProducesTwoBlocksWithPhiFreeMerge() throws Exception {
        Method m = SsaConstructorTest.class.getMethod("clampedRelu", float.class);
        IrFunction fn = SsaConstructor.build(m);
        assertEquals(3, fn.blocks.size(), "entry + true-branch + false-branch");
    }

    @Test
    void branchMatchesRealExecutionForAllSigns() throws Exception {
        Method m = SsaConstructorTest.class.getMethod("clampedRelu", float.class);
        IrFunction fn = SsaConstructor.build(m);
        for (float x : new float[] {-10f, -0.5f, 0f, 0.5f, 10f}) {
            double ir = IrInterpreter.run(fn, new double[] {x});
            double real = clampedRelu(x);
            assertEquals(real, ir, 1e-6, "mismatch for x=" + x);
        }
    }

    @Test
    void loopProducesPhiAtHeader() throws Exception {
        Method m = SsaConstructorTest.class.getMethod("sumTo", int.class);
        IrFunction fn = SsaConstructor.build(m);
        boolean foundMultiPredPhi = fn.blocks.stream()
                .flatMap(b -> b.owned.stream())
                .anyMatch(v -> v instanceof IrValue.PhiValue phi
                        && phi.replacement == null
                        && phi.operands.size() == 2);
        assertTrue(foundMultiPredPhi, "loop header must have a real (non-trivial) 2-operand phi");
    }

    @Test
    void loopMatchesRealExecutionAcrossManyN() throws Exception {
        Method m = SsaConstructorTest.class.getMethod("sumTo", int.class);
        IrFunction fn = SsaConstructor.build(m);
        for (int n = 0; n <= 50; n++) {
            double ir = IrInterpreter.run(fn, new double[] {n});
            double real = sumTo(n);
            assertEquals(real, ir, 1e-9, "mismatch for n=" + n);
        }
    }
}
