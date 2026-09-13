package io.hyperion.compiler.backend.spirv;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.SsaConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * These tests shell out to the real {@code spirv-val} binary (Khronos
 * SPIRV-Tools) rather than asserting our own understanding of validity —
 * if spirv-val isn't on PATH (e.g. a CI image without it), tests are
 * skipped rather than silently passing on unchecked output.
 */
class KernelSpirvEmitterTest {

    @GpuKernel
    public static float fma(float a, float b, float c) {
        return a * b + c;
    }

    @GpuKernel
    public static int dotProduct2(int ax, int ay, int bx, int by) {
        return ax * bx + ay * by;
    }

    @GpuKernel
    public static float branchy(float x) {
        if (x < 0f) return 0f;
        return x;
    }

    @GpuKernel
    public static double usesDouble(double a, double b) {
        return a + b;
    }

    @Test
    void emittedFmaKernelPassesSpirvVal(@TempDir Path tmp) throws Exception {
        assumeSpirvValAvailable();
        Method m = KernelSpirvEmitterTest.class.getMethod("fma", float.class, float.class, float.class);
        byte[] spirv = KernelSpirvEmitter.emit(SsaConstructor.build(m));
        assertSpirvValValid(spirv, tmp);
    }

    @Test
    void emittedIntKernelPassesSpirvVal(@TempDir Path tmp) throws Exception {
        assumeSpirvValAvailable();
        Method m = KernelSpirvEmitterTest.class.getMethod("dotProduct2", int.class, int.class, int.class, int.class);
        byte[] spirv = KernelSpirvEmitter.emit(SsaConstructor.build(m));
        assertSpirvValValid(spirv, tmp);
    }

    @Test
    void moduleStartsWithCorrectMagicNumberAndVersion() throws Exception {
        Method m = KernelSpirvEmitterTest.class.getMethod("fma", float.class, float.class, float.class);
        byte[] spirv = KernelSpirvEmitter.emit(SsaConstructor.build(m));
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(spirv).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x07230203, buf.getInt(0), "SPIR-V magic number");
        assertEquals(0x00010500, buf.getInt(4), "SPIR-V 1.5 version word");
    }

    @Test
    void multiBlockKernelIsRejectedNotMishandled() throws Exception {
        Method m = KernelSpirvEmitterTest.class.getMethod("branchy", float.class);
        IrFunction fn = SsaConstructor.build(m);
        assertThrows(KernelSpirvEmitter.UnsupportedIrShapeException.class, () -> KernelSpirvEmitter.emit(fn));
    }

    @Test
    void doubleKernelIsRejectedNotMishandled() throws Exception {
        Method m = KernelSpirvEmitterTest.class.getMethod("usesDouble", double.class, double.class);
        IrFunction fn = SsaConstructor.build(m);
        assertThrows(KernelSpirvEmitter.UnsupportedIrShapeException.class, () -> KernelSpirvEmitter.emit(fn));
    }

    private static void assumeSpirvValAvailable() {
        try {
            Process p = new ProcessBuilder("spirv-val", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            assumeTrue(p.waitFor() == 0, "spirv-val not usable on this machine");
        } catch (IOException | InterruptedException e) {
            assumeTrue(false, "spirv-val not found on PATH");
        }
    }

    private static void assertSpirvValValid(byte[] spirv, Path tmpDir) throws IOException, InterruptedException {
        Path file = tmpDir.resolve("kernel.spv");
        Files.write(file, spirv);
        Process p = new ProcessBuilder("spirv-val", file.toString()).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        assertEquals(0, exit, "spirv-val rejected the emitted module:\n" + output);
    }
}
