package io.hyperion.compiler.demo;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.compiler.backend.spirv.KernelSpirvEmitter;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.SsaConstructor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Phase4Demo {

    @GpuKernel
    public static float fma(float a, float b, float c) {
        return a * b + c;
    }

    @GpuKernel
    public static float quadratic(float x, float a, float b, float c) {
        return a * x * x + b * x + c;
    }

    @GpuKernel
    public static int dotProduct2(int ax, int ay, int bx, int by) {
        return ax * bx + ay * by;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hyperion Phase 4: Direct SPIR-V Binary Bytecode Generator ===\n");

        Path outDir = Path.of("/tmp/hyperion-spirv-out");
        Files.createDirectories(outDir);

        verify("fma", new Class<?>[] {float.class, float.class, float.class}, outDir);
        verify("quadratic", new Class<?>[] {float.class, float.class, float.class, float.class}, outDir);
        verify("dotProduct2", new Class<?>[] {int.class, int.class, int.class, int.class}, outDir);

        System.out.println("All Phase 4 kernels compiled to real SPIR-V and passed spirv-val.\n");
    }

    private static void verify(String name, Class<?>[] paramTypes, Path outDir) throws Exception {
        System.out.println("--- " + name + " ---");
        Method method = Phase4Demo.class.getMethod(name, paramTypes);
        IrFunction fn = SsaConstructor.build(method);
        byte[] spirv = KernelSpirvEmitter.emit(fn);
        System.out.println("Emitted " + spirv.length + " bytes (" + (spirv.length / 4) + " words) of real SPIR-V");

        Path spvFile = outDir.resolve(name + ".spv");
        Files.write(spvFile, spirv);

        runValidator(spvFile);
        runDisassembler(spvFile);

        System.out.println();
    }

    private static void runValidator(Path file) throws IOException, InterruptedException {
        List<String> cmd = List.of("spirv-val", file.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        System.out.println(exit == 0 ? "[spirv-val] VALID" : "[spirv-val] FAILED:\n" + output);
        if (exit != 0) {
            throw new AssertionError("spirv-val failed for " + file + ":\n" + output);
        }
    }

    private static void runDisassembler(Path file) throws IOException, InterruptedException {
        List<String> cmd = List.of("spirv-dis", "--no-header", file.toString());
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        System.out.println("[spirv-dis]\n" + output);
    }
}
