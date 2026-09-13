package io.hyperion.compiler.demo;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.compiler.backend.vector.VectorKernelExecutor;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.SsaConstructor;

import java.lang.reflect.Method;
import java.util.Random;

public final class Phase7Demo {

    @GpuKernel
    public static float fma(float a, float b, float c) {
        return a * b + c;
    }

    @GpuKernel
    public static float quadratic(float x, float a, float b, float c) {
        return a * x * x + b * x + c;
    }

    /** Deliberately compute-heavy: 10 chained multiply-adds, to test whether higher
     *  arithmetic intensity changes the SIMD-vs-scalar picture differently than the
     *  memory-bandwidth-bound kernels above. */
    @GpuKernel
    public static float heavyPolynomial(float x, float c0) {
        float acc = c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        acc = acc * x + c0;
        return acc;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hyperion Phase 7: CPU Heterogeneous Execution & SIMD Fallback ===\n");
        System.out.println("Vector API lane width on this hardware: " + VectorKernelExecutor.laneWidth()
                + " floats (" + (VectorKernelExecutor.laneWidth() * 32) + "-bit registers)\n");

        correctnessCheck();
        throughputCheck();
        heavyKernelThroughputCheck();

        System.out.println("\nAll Phase 7 checks passed.");
    }

    private static void correctnessCheck() throws Exception {
        System.out.println("--- Correctness: SIMD batch vs. scalar Java, deliberately non-aligned batch size ---");
        Method m = Phase7Demo.class.getMethod("fma", float.class, float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);

        int n = 1_000_003;
        Random rnd = new Random(7);
        float[] a = new float[n], b = new float[n], c = new float[n];
        for (int i = 0; i < n; i++) {
            a[i] = rnd.nextFloat() * 200 - 100;
            b[i] = rnd.nextFloat() * 200 - 100;
            c[i] = rnd.nextFloat() * 200 - 100;
        }

        float[] result = VectorKernelExecutor.executeBatch(fn, new float[][] {a, b, c});

        int mismatches = 0;
        for (int i = 0; i < n; i++) {
            float expected = fma(a[i], b[i], c[i]);
            if (result[i] != expected) {
                mismatches++;
                if (mismatches <= 3) {
                    System.out.printf("  MISMATCH at i=%d: a=%s b=%s c=%s simd=%s scalar=%s%n",
                            i, a[i], b[i], c[i], result[i], expected);
                }
            }
        }
        System.out.println("Checked " + n + " elements (lane width " + VectorKernelExecutor.laneWidth()
                + " does not evenly divide " + n + ", so this exercises the scalar tail path too).");
        System.out.println("Mismatches: " + mismatches + " / " + n);
        if (mismatches > 0) {
            throw new AssertionError(mismatches + " SIMD/scalar mismatches out of " + n);
        }
        System.out.println("[ok] every single element bit-for-bit exact vs. real scalar Java execution\n");
    }

    private static void throughputCheck() throws Exception {
        System.out.println("--- Honest throughput measurement (not a rigorous JMH benchmark — that's Phase 10) ---");
        Method m = Phase7Demo.class.getMethod("quadratic", float.class, float.class, float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);

        int n = 20_000_000;
        Random rnd = new Random(11);
        float[] x = new float[n], pa = new float[n], pb = new float[n], pc = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextFloat() * 10 - 5;
            pa[i] = rnd.nextFloat() * 2 - 1;
            pb[i] = rnd.nextFloat() * 2 - 1;
            pc[i] = rnd.nextFloat() * 2 - 1;
        }
        float[][] inputs = {x, pa, pb, pc};

        for (int w = 0; w < 3; w++) {
            VectorKernelExecutor.executeBatch(fn, inputs);
            scalarQuadratic(x, pa, pb, pc);
        }

        long simdStart = System.nanoTime();
        float[] simdResult = VectorKernelExecutor.executeBatch(fn, inputs);
        long simdNanos = System.nanoTime() - simdStart;

        long scalarStart = System.nanoTime();
        float[] scalarResult = scalarQuadratic(x, pa, pb, pc);
        long scalarNanos = System.nanoTime() - scalarStart;

        int mismatches = 0;
        for (int i = 0; i < n; i++) if (simdResult[i] != scalarResult[i]) mismatches++;

        double simdMs = simdNanos / 1_000_000.0;
        double scalarMs = scalarNanos / 1_000_000.0;
        System.out.printf("N = %,d elements%n", n);
        System.out.printf("SIMD (Vector API, lane width %d):  %.2f ms  (%.1f M elem/sec)%n",
                VectorKernelExecutor.laneWidth(), simdMs, n / simdMs / 1000.0);
        System.out.printf("Scalar (plain Java loop):          %.2f ms  (%.1f M elem/sec)%n",
                scalarMs, n / scalarMs / 1000.0);
        System.out.printf("Speedup: %.2fx%n", scalarMs / simdMs);
        System.out.println("Result mismatches between SIMD and scalar paths: " + mismatches);
        if (mismatches > 0) {
            throw new AssertionError(mismatches + " mismatches in throughput comparison");
        }
    }

    private static float[] scalarQuadratic(float[] x, float[] a, float[] b, float[] c) {
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            out[i] = a[i] * x[i] * x[i] + b[i] * x[i] + c[i];
        }
        return out;
    }

    private static void heavyKernelThroughputCheck() throws Exception {
        System.out.println("\n--- Same measurement, a compute-heavier kernel (10 chained multiply-adds) ---");
        Method m = Phase7Demo.class.getMethod("heavyPolynomial", float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);

        int n = 20_000_000;
        Random rnd = new Random(13);
        float[] x = new float[n], c0 = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = rnd.nextFloat() * 2 - 1;
            c0[i] = rnd.nextFloat() * 2 - 1;
        }
        float[][] inputs = {x, c0};

        for (int w = 0; w < 3; w++) {
            VectorKernelExecutor.executeBatch(fn, inputs);
            scalarHeavyPolynomial(x, c0);
        }

        long simdStart = System.nanoTime();
        float[] simdResult = VectorKernelExecutor.executeBatch(fn, inputs);
        long simdNanos = System.nanoTime() - simdStart;

        long scalarStart = System.nanoTime();
        float[] scalarResult = scalarHeavyPolynomial(x, c0);
        long scalarNanos = System.nanoTime() - scalarStart;

        int mismatches = 0;
        for (int i = 0; i < n; i++) if (simdResult[i] != scalarResult[i]) mismatches++;

        double simdMs = simdNanos / 1_000_000.0;
        double scalarMs = scalarNanos / 1_000_000.0;
        System.out.printf("N = %,d elements, 10 multiply-adds/element (18 flops vs. quadratic's 4)%n", n);
        System.out.printf("SIMD (Vector API, lane width %d):  %.2f ms  (%.1f M elem/sec)%n",
                VectorKernelExecutor.laneWidth(), simdMs, n / simdMs / 1000.0);
        System.out.printf("Scalar (plain Java loop):          %.2f ms  (%.1f M elem/sec)%n",
                scalarMs, n / scalarMs / 1000.0);
        System.out.printf("Speedup: %.2fx%n", scalarMs / simdMs);
        if (mismatches > 0) {
            throw new AssertionError(mismatches + " mismatches in heavy-kernel throughput comparison");
        }
    }

    private static float[] scalarHeavyPolynomial(float[] x, float[] c0) {
        float[] out = new float[x.length];
        for (int i = 0; i < x.length; i++) {
            float acc = c0[i];
            for (int k = 0; k < 9; k++) acc = acc * x[i] + c0[i];
            out[i] = acc;
        }
        return out;
    }
}
