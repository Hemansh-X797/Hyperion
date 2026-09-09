package io.hyperion.core.reflection;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.core.reflection.ast.KernelAst;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

class AstExtractorTest {

    @GpuKernel
    public static float fma(float a, float b, float c) {
        return a * b + c;
    }

    @GpuKernel
    public static float branchy(float x) {
        if (x < 0f) return 0f;
        return x;
    }

    @GpuKernel
    public static int callsAnother(int x) {
        return Math.abs(x);
    }

    @Test
    void extractsCorrectAstForFma() throws Exception {
        Method m = AstExtractorTest.class.getMethod("fma", float.class, float.class, float.class);
        KernelAst ast = AstExtractor.extract(m);
        assertEquals("fma", ast.methodName());
        assertEquals(3, ast.parameters().size());
        assertEquals("((p0 * p1) + p2)", ast.body().toString());
    }

    @Test
    void extractedAstMatchesRealExecutionAcrossManyInputs() throws Exception {
        Method m = AstExtractorTest.class.getMethod("fma", float.class, float.class, float.class);
        KernelAst ast = AstExtractor.extract(m);

        for (float a = -3f; a <= 3f; a += 1.3f) {
            for (float b = -2f; b <= 2f; b += 0.7f) {
                float c = a - b;
                float real = fma(a, b, c);
                double fromAst = ast.body().evaluate(new double[] {a, b, c});
                assertEquals(real, fromAst, 1e-5,
                        "AST evaluation diverged from real bytecode execution for a=" + a + " b=" + b + " c=" + c);
            }
        }
    }

    @Test
    void rejectsBranchyKernel() throws Exception {
        Method m = AstExtractorTest.class.getMethod("branchy", float.class);
        assertThrows(AstExtractor.UnsupportedKernelShapeException.class, () -> AstExtractor.extract(m));
    }

    @Test
    void rejectsKernelThatCallsAnotherMethod() throws Exception {
        Method m = AstExtractorTest.class.getMethod("callsAnother", int.class);
        assertThrows(AstExtractor.UnsupportedKernelShapeException.class, () -> AstExtractor.extract(m));
    }
}
