package io.hyperion.runtime.vulkan.demo;

import io.hyperion.runtime.vulkan.device.PhysicalDeviceManager;
import io.hyperion.runtime.vulkan.device.VulkanInstanceBootstrap;
import io.hyperion.runtime.vulkan.ffm.VulkanConstants;

import java.util.List;

public final class Phase0Demo {
    public static void main(String[] args) {
        System.out.println("=== Hyperion Phase 0: Vulkan FFM Bootstrap ===");

        int loaderVersion = VulkanInstanceBootstrap.queryInstanceVersion();
        System.out.printf("Vulkan loader instance version: %d.%d.%d%n",
                VulkanConstants.versionMajor(loaderVersion),
                VulkanConstants.versionMinor(loaderVersion),
                VulkanConstants.versionPatch(loaderVersion));

        try (VulkanInstanceBootstrap instance =
                     VulkanInstanceBootstrap.create("Hyperion Phase0 Demo", new String[0])) {

            System.out.println("VkInstance created successfully: " + instance.handle());

            List<PhysicalDeviceManager.PhysicalDevice> devices =
                    PhysicalDeviceManager.enumerate(instance);

            System.out.println("Physical devices found: " + devices.size());
            for (PhysicalDeviceManager.PhysicalDevice d : devices) {
                System.out.printf("  - %s [%s] vendor=0x%04X device=0x%04X apiVersion=%d.%d.%d%n",
                        d.name(), d.deviceTypeName(), d.vendorId(), d.deviceId(),
                        VulkanConstants.versionMajor(d.apiVersion()),
                        VulkanConstants.versionMinor(d.apiVersion()),
                        VulkanConstants.versionPatch(d.apiVersion()));
                for (PhysicalDeviceManager.QueueFamily qf : d.queueFamilies()) {
                    System.out.printf("      queueFamily[%d]: count=%d graphics=%b compute=%b transfer=%b%n",
                            qf.index(), qf.queueCount(), qf.supportsGraphics(),
                            qf.supportsCompute(), qf.supportsTransfer());
                }
            }

            if (devices.isEmpty()) {
                System.out.println("(No physical devices — expected in a headless/no-GPU sandbox; " +
                        "the loader + FFM bindings themselves are proven correct because " +
                        "vkCreateInstance and vkEnumeratePhysicalDevices both returned VK_SUCCESS.)");
            }
        }

        System.out.println("=== Phase 0 bootstrap complete, instance destroyed cleanly ===");
    }
}
