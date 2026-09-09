package io.hyperion.core.demo;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.core.reflection.AstExtractor;
import io.hyperion.core.reflection.ast.KernelAst;

import java.lang.reflect.Method;
import java.util.Random;

public final class Phase2Demo {

    // --- Real annotated kernel candidates: these are ordinary compiled Java
    //     methods. AstExtractor reads their *actual bytecode*, not source. ---

    @GpuKernel
    public static float fma(float a, float b, float c) {
        return a * b + c;
    }

    @GpuKernel
    public static float quadratic(float x, float a, float b, float c) {
        return a * x * x + b * x + c;
    }

    @GpuKernel
    public static double average3(double a, double b, double c) {
        return (a + b + c) / 3.0;
    }

    @GpuKernel
    public static int dotProduct2(int ax, int ay, int bx, int by) {
        return ax * bx + ay * by;
    }

    // A deliberately unsupported kernel shape (has a branch) to prove the
    // extractor correctly rejects it instead of silently mis-extracting.
    @GpuKernel
    public static float clampedRelu(float x) {
        if (x < 0f) {
            return 0f;
        }
        return x;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hyperion Phase 2: Code Reflection & AST Capture (stock-JDK classfile) ===\n");

        runAndVerify("fma", new Object[] {2.0f, 3.0f, 4.0f});
        runAndVerify("quadratic", new Object[] {2.0f, 1.0f, -3.0f, 5.0f});
        runAndVerify("average3", new Object[] {10.0, 20.0, 30.0});
        runAndVerify("dotProduct2", new Object[] {3, 4, 5, 6});

        System.out.println("--- Negative test: rejecting an unsupported kernel shape ---");
        Method clamped = Phase2Demo.class.getMethod("clampedRelu", float.class);
        try {
            AstExtractor.extract(clamped);
            throw new AssertionError("Expected extraction of clampedRelu to be rejected!");
        } catch (AstExtractor.UnsupportedKernelShapeException e) {
            System.out.println("[ok] correctly rejected branchy kernel: " + e.getMessage());
        }

        System.out.println("\nAll Phase 2 correctness checks passed.");
    }

    private static void runAndVerify(String methodName, Object[] args) throws Exception {
        Class<?>[] paramTypes = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            paramTypes[i] = unbox(args[i].getClass());
        }
        Method method = Phase2Demo.class.getMethod(methodName, paramTypes);

        KernelAst ast = AstExtractor.extract(method);
        System.out.println("Extracted AST for " + methodName + ":");
        System.out.println("  " + ast);

        // Evaluate the extracted expression tree with random-ish sample args.
        double[] paramValues = new double[args.length];
        for (int i = 0; i < args.length; i++) {
            paramValues[i] = ((Number) args[i]).doubleValue();
        }
        double astResult = ast.body().evaluate(paramValues);

        // Independently invoke the REAL compiled method via reflection.
        Object reflectResult = method.invoke(null, args);
        double reflectValue = ((Number) reflectResult).doubleValue();

        System.out.printf("  AST-evaluated result: %s%n", astResult);
        System.out.printf("  Reflective-invoke result: %s%n", reflectValue);

        if (Math.abs(astResult - reflectValue) > 1e-6) {
            throw new AssertionError(
                    "MISMATCH for " + methodName + ": AST gave " + astResult + " but real method gave " + reflectValue);
        }
        System.out.println("  [ok] AST evaluation matches real bytecode execution exactly\n");
    }

    private static Class<?> unbox(Class<?> boxed) {
        if (boxed == Float.class) return float.class;
        if (boxed == Double.class) return double.class;
        if (boxed == Integer.class) return int.class;
        if (boxed == Long.class) return long.class;
        return boxed;
    }
}
