package io.hyperion.compiler.demo;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.compiler.backend.spirv.KernelSpirvEmitter;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.IrType;
import io.hyperion.core.ir.SsaConstructor;
import io.hyperion.runtime.vulkan.device.PhysicalDeviceManager;
import io.hyperion.runtime.vulkan.device.VulkanInstanceBootstrap;
import io.hyperion.runtime.vulkan.pipeline.ComputeDispatcher;

import java.lang.reflect.Method;
import java.util.List;

/**
 * The payoff: Phase 0's real Vulkan FFM bindings and Phase 4's real SPIR-V
 * emitter meet for the first time. A Java method is compiled all the way
 * from bytecode -> SSA IR -> SPIR-V -> an actual GPU (or software rasterizer
 * ICD) dispatch, and the device's own computed answer is checked against
 * genuinely invoking the real compiled Java method — end to end, no step
 * simulated.
 */
public final class Phase5Demo {

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
        System.out.println("=== Hyperion Phase 5: Vulkan Compute Pipeline & Command Queue Engine ===\n");
        System.out.println("(Java bytecode -> SSA IR -> SPIR-V -> real GPU dispatch -> readback)\n");

        try (VulkanInstanceBootstrap instance = VulkanInstanceBootstrap.create("Hyperion Phase5 Demo", new String[0])) {
            List<PhysicalDeviceManager.PhysicalDevice> devices = PhysicalDeviceManager.enumerate(instance);
            if (devices.isEmpty()) {
                System.out.println("No physical devices available — cannot run Phase 5 on this machine.");
                return;
            }
            PhysicalDeviceManager.PhysicalDevice device = devices.get(0);
            System.out.println("Dispatching on: " + device.name() + " [" + device.deviceTypeName() + "]\n");

            try (ComputeDispatcher dispatcher = ComputeDispatcher.create(device)) {
                runFloat(dispatcher, "fma", new Class<?>[] {float.class, float.class, float.class},
                        new Object[] {2.0f, 3.0f, 4.0f});
                runFloat(dispatcher, "quadratic", new Class<?>[] {float.class, float.class, float.class, float.class},
                        new Object[] {2.0f, 1.0f, -3.0f, 5.0f});
                runInt(dispatcher, "dotProduct2", new Class<?>[] {int.class, int.class, int.class, int.class},
                        new Object[] {3, 4, 5, 6});
            }
        }

        System.out.println("\nAll Phase 5 kernels executed on the real device and matched real Java execution exactly.");
    }

    private static void runFloat(ComputeDispatcher dispatcher, String name, Class<?>[] paramTypes, Object[] args) throws Exception {
        run(dispatcher, name, paramTypes, args, true);
    }

    private static void runInt(ComputeDispatcher dispatcher, String name, Class<?>[] paramTypes, Object[] args) throws Exception {
        run(dispatcher, name, paramTypes, args, false);
    }

    private static void run(ComputeDispatcher dispatcher, String name, Class<?>[] paramTypes, Object[] args, boolean isFloat) throws Exception {
        Method method = Phase5Demo.class.getMethod(name, paramTypes);
        IrFunction fn = SsaConstructor.build(method);
        byte[] spirv = KernelSpirvEmitter.emit(fn);

        double[] inputs = new double[args.length];
        for (int i = 0; i < args.length; i++) inputs[i] = ((Number) args[i]).doubleValue();

        double gpuResult = dispatcher.dispatch(spirv, fn.name, inputs, isFloat);
        Object realResult = method.invoke(null, args);
        double realValue = ((Number) realResult).doubleValue();

        String status = Math.abs(gpuResult - realValue) < 1e-5 ? "MATCH" : "MISMATCH";
        System.out.printf("--- %s%s ---%n", name, java.util.Arrays.toString(args));
        System.out.printf("  GPU result:  %s%n", gpuResult);
        System.out.printf("  Java result: %s%n", realValue);
        System.out.printf("  [%s]%n%n", status);

        if (!status.equals("MATCH")) {
            throw new AssertionError(name + " GPU/Java mismatch: gpu=" + gpuResult + " java=" + realValue);
        }
    }
}
