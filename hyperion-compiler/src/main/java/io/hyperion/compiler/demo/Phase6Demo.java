package io.hyperion.compiler.demo;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.compiler.backend.spirv.KernelSpirvEmitter;
import io.hyperion.compiler.passes.DeadCodeEliminationPass;
import io.hyperion.compiler.passes.LoopUnrollerPass;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.IrInterpreter;
import io.hyperion.core.ir.IrPrinter;
import io.hyperion.core.ir.SsaConstructor;
import io.hyperion.runtime.vulkan.device.PhysicalDeviceManager;
import io.hyperion.runtime.vulkan.device.VulkanInstanceBootstrap;
import io.hyperion.runtime.vulkan.pipeline.ComputeDispatcher;

import java.lang.reflect.Method;
import java.util.List;

public final class Phase6Demo {

    /** A fixed, compile-time-constant 4-step Euler integration — a real pattern
     *  (bounded numerical solvers, FIR filters) that SsaConstructor compiles to a
     *  genuine 4-block loop, which Phase 4/5 previously could NOT dispatch to a
     *  GPU at all (multi-block kernels are rejected). Unrolling changes that. */
    @GpuKernel
    public static float integrateFixed(float x0, float dx) {
        float x = x0;
        for (int i = 0; i < 4; i++) {
            x = x + dx * x;
        }
        return x;
    }

    /** Data-dependent bound (n is a parameter, not a constant) — the unroller
     *  must correctly REFUSE this one, not silently mis-unroll it. */
    @GpuKernel
    public static int sumTo(int n) {
        int s = 0;
        for (int i = 0; i < n; i++) {
            s = s + i;
        }
        return s;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hyperion Phase 6: GPU Kernel Optimizations (loop unrolling + DCE) ===\n");

        Method m = Phase6Demo.class.getMethod("integrateFixed", float.class, float.class);
        IrFunction original = SsaConstructor.build(m);
        System.out.println("--- Before unrolling (" + original.blocks.size() + " blocks) ---");
        System.out.println(IrPrinter.print(original));

        // 1. Try compiling the ORIGINAL loop-bearing kernel to SPIR-V — must be rejected.
        boolean rejectedBeforeUnroll = false;
        try {
            KernelSpirvEmitter.emit(original);
        } catch (KernelSpirvEmitter.UnsupportedIrShapeException e) {
            rejectedBeforeUnroll = true;
            System.out.println("\n[expected] SPIR-V backend correctly rejects the loop-bearing kernel: " + e.getMessage());
        }
        if (!rejectedBeforeUnroll) {
            throw new AssertionError("Expected the un-unrolled multi-block kernel to be rejected by KernelSpirvEmitter");
        }

        // 2. Unroll, then DCE.
        IrFunction unrolled = LoopUnrollerPass.unroll(original);
        IrFunction optimized = DeadCodeEliminationPass.run(unrolled);
        System.out.println("\n--- After unrolling + DCE (" + optimized.blocks.size() + " block) ---");
        System.out.println(IrPrinter.print(optimized));

        // 3. Correctness: unrolled IR must match real Java execution, same rigor as every prior phase.
        System.out.println("\n--- Correctness: unrolled IR vs. real Java execution ---");
        for (float[] pair : new float[][] {{1.0f, 0.1f}, {2.0f, -0.05f}, {0.0f, 0.5f}, {10.0f, 0.01f}}) {
            double ir = IrInterpreter.run(optimized, new double[] {pair[0], pair[1]});
            double real = integrateFixed(pair[0], pair[1]);
            System.out.printf("  x0=%s dx=%s -> IR=%s java=%s [%s]%n",
                    pair[0], pair[1], ir, real, Math.abs(ir - real) < 1e-4 ? "ok" : "MISMATCH");
            if (Math.abs(ir - real) >= 1e-4) throw new AssertionError("unrolled IR mismatch");
        }

        // 4. The payoff: the UNROLLED kernel now compiles to SPIR-V and runs on the real GPU.
        System.out.println("\n--- The payoff: unrolled kernel now compiles to SPIR-V and dispatches on real GPU ---");
        byte[] spirv = KernelSpirvEmitter.emit(optimized);
        System.out.println("Emitted " + spirv.length + " bytes of SPIR-V from a kernel that had a loop 30 seconds ago.");

        try (VulkanInstanceBootstrap instance = VulkanInstanceBootstrap.create("Phase6 Demo", new String[0])) {
            List<PhysicalDeviceManager.PhysicalDevice> devices = PhysicalDeviceManager.enumerate(instance);
            if (devices.isEmpty()) {
                System.out.println("(No physical device available in this environment to dispatch on.)");
            } else {
                PhysicalDeviceManager.PhysicalDevice device = devices.get(0);
                try (ComputeDispatcher dispatcher = ComputeDispatcher.create(device)) {
                    for (float[] pair : new float[][] {{1.0f, 0.1f}, {2.0f, -0.05f}}) {
                        double gpu = dispatcher.dispatch(spirv, optimized.name, new double[] {pair[0], pair[1]}, true);
                        double real = integrateFixed(pair[0], pair[1]);
                        System.out.printf("  GPU dispatch: x0=%s dx=%s -> gpu=%s java=%s [%s]%n",
                                pair[0], pair[1], gpu, real, Math.abs(gpu - real) < 1e-4 ? "MATCH" : "MISMATCH");
                        if (Math.abs(gpu - real) >= 1e-4) throw new AssertionError("GPU mismatch after unroll");
                    }
                }
            }
        }

        // 5. Honesty check: the unroller must REFUSE a data-dependent-bound loop, not mis-unroll it.
        System.out.println("\n--- Honesty check: refusing to unroll a data-dependent loop bound ---");
        Method sumToMethod = Phase6Demo.class.getMethod("sumTo", int.class);
        IrFunction sumToFn = SsaConstructor.build(sumToMethod);
        try {
            LoopUnrollerPass.unroll(sumToFn);
            throw new AssertionError("Expected sumTo(n) to be rejected — n is a parameter, not a compile-time constant!");
        } catch (LoopUnrollerPass.UnsupportedLoopShapeException e) {
            System.out.println("[ok] correctly refused: " + e.getMessage());
        }

        System.out.println("\nAll Phase 6 checks passed.");
    }
}
