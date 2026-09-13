package io.hyperion.compiler.backend.vector;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.SsaConstructor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class VectorKernelExecutorTest {

    @GpuKernel
    public static float fma(float a, float b, float c) {
        return a * b + c;
    }

    @GpuKernel
    public static int intKernel(int a, int b) {
        return a + b;
    }

    @Test
    void batchExecutionMatchesScalarAcrossNonAlignedSize() throws Exception {
        Method m = VectorKernelExecutorTest.class.getMethod("fma", float.class, float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);

        int n = VectorKernelExecutor.laneWidth() * 100 + 7; // deliberately not a multiple of lane width
        Random rnd = new Random(99);
        float[] a = new float[n], b = new float[n], c = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextFloat() * 100 - 50;
            b[i] = rnd.nextFloat() * 100 - 50;
            c[i] = rnd.nextFloat() * 100 - 50;
        }

        float[] result = VectorKernelExecutor.executeBatch(fn, new float[][] {a, b, c});
        for (int i = 0; i < n; i++) {
            assertEquals(fma(a[i], b[i], c[i]), result[i], "mismatch at index " + i);
        }
    }

    @Test
    void singleElementBatchWorks() throws Exception {
        Method m = VectorKernelExecutorTest.class.getMethod("fma", float.class, float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);
        float[] result = VectorKernelExecutor.executeBatch(fn, new float[][] {{2f}, {3f}, {4f}});
        assertEquals(1, result.length);
        assertEquals(10f, result[0]);
    }

    @Test
    void emptyBatchWorks() throws Exception {
        Method m = VectorKernelExecutorTest.class.getMethod("fma", float.class, float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);
        float[] result = VectorKernelExecutor.executeBatch(fn, new float[][] {{}, {}, {}});
        assertEquals(0, result.length);
    }

    @Test
    void rejectsIntKernels() throws Exception {
        Method m = VectorKernelExecutorTest.class.getMethod("intKernel", int.class, int.class);
        IrFunction fn = SsaConstructor.build(m);
        assertThrows(VectorKernelExecutor.UnsupportedIrShapeException.class,
                () -> VectorKernelExecutor.executeBatch(fn, new float[][] {{1f}, {2f}}));
    }

    @Test
    void rejectsMismatchedArrayLengths() throws Exception {
        Method m = VectorKernelExecutorTest.class.getMethod("fma", float.class, float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);
        assertThrows(IllegalArgumentException.class,
                () -> VectorKernelExecutor.executeBatch(fn, new float[][] {{1f, 2f}, {1f}, {1f}}));
    }
}
