package io.hyperion.core.demo;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.IrInterpreter;
import io.hyperion.core.ir.IrPrinter;
import io.hyperion.core.ir.SsaConstructor;

import java.lang.reflect.Method;

public final class Phase3Demo {

    // --- Real kernels with actual control flow: this is exactly what Phase 2
    //     rejected (branches/loops). Phase 3 must handle both correctly. ---

    @GpuKernel
    public static float clampedRelu(float x) {
        if (x < 0f) {
            return 0f;
        }
        return x;
    }

    @GpuKernel
    public static int max2(int a, int b) {
        if (a > b) {
            return a;
        }
        return b;
    }

    @GpuKernel
    public static int sumTo(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s = s + i;
        }
        return s;
    }

    @GpuKernel
    public static int power(int base, int exponent) {
        int result = 1;
        int i = 0;
        while (i < exponent) {
            result = result * base;
            i = i + 1;
        }
        return result;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hyperion Phase 3: Hyperion SSA IR (real CFG + Braun et al. SSA construction) ===\n");

        verifyBranch("clampedRelu", new Class<?>[] {float.class}, new Object[][] {
                {-5.0f}, {0.0f}, {3.5f}, {-0.001f}, {100.0f}
        });

        verifyBranch("max2", new Class<?>[] {int.class, int.class}, new Object[][] {
                {3, 7}, {7, 3}, {5, 5}, {-2, -8}, {0, 0}
        });

        verifyLoop("sumTo", new Class<?>[] {int.class}, new Object[][] {
                {0}, {1}, {5}, {10}, {100}
        });

        verifyLoop("power", new Class<?>[] {int.class, int.class}, new Object[][] {
                {2, 0}, {2, 10}, {3, 5}, {7, 1}, {5, 0}
        });

        System.out.println("All Phase 3 correctness checks passed — branches and loops both verified.\n");
    }

    private static void verifyBranch(String name, Class<?>[] paramTypes, Object[][] testCases) throws Exception {
        System.out.println("--- " + name + " (if/else) ---");
        run(name, paramTypes, testCases);
    }

    private static void verifyLoop(String name, Class<?>[] paramTypes, Object[][] testCases) throws Exception {
        System.out.println("--- " + name + " (loop) ---");
        run(name, paramTypes, testCases);
    }

    private static void run(String name, Class<?>[] paramTypes, Object[][] testCases) throws Exception {
        Method method = Phase3Demo.class.getMethod(name, paramTypes);
        IrFunction fn = SsaConstructor.build(method);
        System.out.println(IrPrinter.print(fn));

        for (Object[] args : testCases) {
            double[] paramValues = new double[args.length];
            for (int i = 0; i < args.length; i++) paramValues[i] = ((Number) args[i]).doubleValue();

            double irResult = IrInterpreter.run(fn, paramValues);
            Object realResult = method.invoke(null, args);
            double realValue = ((Number) realResult).doubleValue();

            String status = Math.abs(irResult - realValue) < 1e-6 ? "ok" : "MISMATCH";
            System.out.printf("  args=%s -> IR=%s real=%s [%s]%n",
                    java.util.Arrays.toString(args), irResult, realValue, status);

            if (!status.equals("ok")) {
                throw new AssertionError(name + " MISMATCH for args " + java.util.Arrays.toString(args)
                        + ": IR gave " + irResult + " but real execution gave " + realValue);
            }
        }
        System.out.println();
    }
}
