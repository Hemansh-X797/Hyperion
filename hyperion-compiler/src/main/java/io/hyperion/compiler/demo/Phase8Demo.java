package io.hyperion.compiler.demo;

import io.hyperion.api.annotation.GpuKernel;
import io.hyperion.compiler.backend.spirv.KernelSpirvEmitter;
import io.hyperion.compiler.backend.vector.VectorKernelExecutor;
import io.hyperion.core.ir.IrFunction;
import io.hyperion.core.ir.SsaConstructor;
import io.hyperion.core.scheduler.HyperionScheduler;
import io.hyperion.runtime.vulkan.device.PhysicalDeviceManager;
import io.hyperion.runtime.vulkan.device.VulkanInstanceBootstrap;
import io.hyperion.runtime.vulkan.pipeline.ComputeDispatcher;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CompletableFuture;

/**
 * Real concurrent execution across heterogeneous backends: several
 * independent logical-device GPU dispatchers (Phase 5) and CPU SIMD batch
 * jobs (Phase 7), submitted as a mixed pool of tasks to
 * {@link HyperionScheduler}'s virtual-thread workers, which work-steal
 * across each other exactly like Phase 8's roadmap called for — just with
 * genuine Hyperion kernel executions as the payload instead of a toy
 * example.
 */
public final class Phase8Demo {

    @GpuKernel
    public static float fma(float a, float b, float c) {
        return a * b + c;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("=== Hyperion Phase 8: Virtual Thread Task Scheduler ===\n");

        Method m = Phase8Demo.class.getMethod("fma", float.class, float.class, float.class);
        IrFunction fn = SsaConstructor.build(m);
        byte[] spirv = KernelSpirvEmitter.emit(fn);

        try (VulkanInstanceBootstrap instance = VulkanInstanceBootstrap.create("Phase8 Demo", new String[0])) {
            List<PhysicalDeviceManager.PhysicalDevice> devices = PhysicalDeviceManager.enumerate(instance);
            if (devices.isEmpty()) {
                System.out.println("No physical device available — running CPU-only tasks instead.\n");
                runCpuOnly(fn);
                return;
            }
            PhysicalDeviceManager.PhysicalDevice device = devices.get(0);

            // Two independent logical devices from the same physical device — simulates
            // "multiple GPU queues" for the work-stealing scheduler to spread work across,
            // matching the roadmap's "balance tensor workloads across multiple physical
            // GPUs" intent even on hardware with just one real device.
            int gpuDeviceCount = 2;
            List<ComputeDispatcher> dispatchers = new ArrayList<>();
            for (int i = 0; i < gpuDeviceCount; i++) {
                dispatchers.add(ComputeDispatcher.create(device));
                System.out.println("Created independent logical device #" + i);
            }

            int workerCount = 4;
            try (HyperionScheduler scheduler = new HyperionScheduler(workerCount)) {
                System.out.println("\nScheduler started with " + workerCount + " virtual-thread workers "
                        + "(work-stealing Chase-Lev deques)\n");

                int gpuTaskCount = 40;
                int cpuTaskCount = 40;
                Random rnd = new Random(2026);

                List<CompletableFuture<double[]>> futures = new ArrayList<>();
                List<float[]> expectedArgs = new ArrayList<>();

                for (int i = 0; i < gpuTaskCount; i++) {
                    float a = rnd.nextFloat() * 100 - 50, b = rnd.nextFloat() * 100 - 50, c = rnd.nextFloat() * 100 - 50;
                    ComputeDispatcher dispatcher = dispatchers.get(i % gpuDeviceCount);
                    expectedArgs.add(new float[] {a, b, c, 1f}); // last flag: 1=gpu task, 0=cpu task
                    futures.add(scheduler.submit(() ->
                            new double[] {dispatcher.dispatch(spirv, fn.name, new double[] {a, b, c}, true)}));
                }
                for (int i = 0; i < cpuTaskCount; i++) {
                    int batchSize = 1000 + rnd.nextInt(5000);
                    float[] a = new float[batchSize], b = new float[batchSize], c = new float[batchSize];
                    for (int k = 0; k < batchSize; k++) {
                        a[k] = rnd.nextFloat() * 100 - 50;
                        b[k] = rnd.nextFloat() * 100 - 50;
                        c[k] = rnd.nextFloat() * 100 - 50;
                    }
                    expectedArgs.add(new float[] {a[0], b[0], c[0], 0f});
                    float[] aF = a, bF = b, cF = c;
                    futures.add(scheduler.submit(() -> {
                        float[] result = VectorKernelExecutor.executeBatch(fn, new float[][] {aF, bF, cF});
                        double[] boxed = new double[result.length];
                        for (int k = 0; k < result.length; k++) boxed[k] = result[k];
                        return boxed;
                    }));
                }

                long start = System.nanoTime();
                int mismatches = 0;
                for (int i = 0; i < futures.size(); i++) {
                    double[] result = futures.get(i).get();
                    float[] argsAndFlag = expectedArgs.get(i);
                    float expected = fma(argsAndFlag[0], argsAndFlag[1], argsAndFlag[2]);
                    if (Math.abs(result[0] - expected) > 1e-3) {
                        mismatches++;
                        System.out.println("MISMATCH on task " + i + ": got " + result[0] + " expected " + expected);
                    }
                }
                long elapsedMs = (System.nanoTime() - start) / 1_000_000;

                System.out.println("Submitted " + futures.size() + " mixed GPU+CPU tasks ("
                        + gpuTaskCount + " GPU dispatches across " + gpuDeviceCount + " logical devices, "
                        + cpuTaskCount + " CPU SIMD batches)");
                System.out.println("All results collected in " + elapsedMs + " ms");
                System.out.println("Mismatches: " + mismatches + " / " + futures.size());

                if (mismatches > 0) {
                    throw new AssertionError(mismatches + " task result mismatches");
                }
            }

            for (ComputeDispatcher d : dispatchers) d.close();
        }

        System.out.println("\nAll Phase 8 checks passed.");
    }

    private static void runCpuOnly(IrFunction fn) throws Exception {
        int workerCount = 4;
        try (HyperionScheduler scheduler = new HyperionScheduler(workerCount)) {
            List<CompletableFuture<double[]>> futures = new ArrayList<>();
            Random rnd = new Random(1);
            for (int i = 0; i < 20; i++) {
                int batchSize = 1000;
                float[] a = new float[batchSize], b = new float[batchSize], c = new float[batchSize];
                for (int k = 0; k < batchSize; k++) {
                    a[k] = rnd.nextFloat(); b[k] = rnd.nextFloat(); c[k] = rnd.nextFloat();
                }
                futures.add(scheduler.submit(() -> {
                    float[] r = VectorKernelExecutor.executeBatch(fn, new float[][] {a, b, c});
                    double[] boxed = new double[r.length];
                    for (int k = 0; k < r.length; k++) boxed[k] = r[k];
                    return boxed;
                }));
            }
            for (var f : futures) f.get();
            System.out.println("Ran " + futures.size() + " CPU-only SIMD tasks through the scheduler successfully.");
        }
    }
}
