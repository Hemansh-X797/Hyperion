package io.hyperion.runtime.vulkan.device;

import io.hyperion.runtime.vulkan.ffm.StructLayouts;
import io.hyperion.runtime.vulkan.ffm.VulkanConstants;
import io.hyperion.runtime.vulkan.ffm.VulkanFFM;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Enumerates physical GPUs visible to a VkInstance and their queue families,
 * entirely through FFM downcalls — this is the real hardware discovery layer
 * Hyperion's scheduler (Phase 8) will select devices from.
 */
public final class PhysicalDeviceManager {

    public record QueueFamily(int index, int queueCount, int flags, int timestampValidBits) {
        public boolean supportsGraphics() { return (flags & VulkanConstants.VK_QUEUE_GRAPHICS_BIT) != 0; }
        public boolean supportsCompute() { return (flags & VulkanConstants.VK_QUEUE_COMPUTE_BIT) != 0; }
        public boolean supportsTransfer() { return (flags & VulkanConstants.VK_QUEUE_TRANSFER_BIT) != 0; }
    }

    public record PhysicalDevice(
            MemorySegment handle,
            String name,
            int vendorId,
            int deviceId,
            int deviceType,
            int apiVersion,
            int driverVersion,
            List<QueueFamily> queueFamilies
    ) {
        public String deviceTypeName() {
            return switch (deviceType) {
                case VulkanConstants.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "Discrete GPU";
                case VulkanConstants.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "Integrated GPU";
                case VulkanConstants.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "Virtual GPU";
                case VulkanConstants.VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU (software rasterizer)";
                default -> "Other";
            };
        }

        /** First queue family index that supports dedicated/general compute dispatch. */
        public int firstComputeQueueFamily() {
            for (QueueFamily qf : queueFamilies) {
                if (qf.supportsCompute()) return qf.index();
            }
            throw new IllegalStateException("No compute-capable queue family on device: " + name);
        }
    }

    private PhysicalDeviceManager() {}

    public static List<PhysicalDevice> enumerate(VulkanInstanceBootstrap instance) {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment instanceHandle = instance.handle();

            MemorySegment pCount = a.allocate(JAVA_INT);
            int r = (int) VulkanFFM.vkEnumeratePhysicalDevices.invokeExact(
                    (MemorySegment) instanceHandle, (MemorySegment) pCount, (MemorySegment) MemorySegment.NULL);
            VulkanFFM.check(r, "vkEnumeratePhysicalDevices(count)");
            int count = pCount.get(JAVA_INT, 0);

            List<PhysicalDevice> devices = new ArrayList<>(count);
            if (count == 0) return devices;

            MemorySegment deviceArray = a.allocate(ADDRESS, count);
            r = (int) VulkanFFM.vkEnumeratePhysicalDevices.invokeExact(
                    (MemorySegment) instanceHandle, (MemorySegment) pCount, (MemorySegment) deviceArray);
            VulkanFFM.check(r, "vkEnumeratePhysicalDevices(fill)");

            for (int i = 0; i < count; i++) {
                MemorySegment device = deviceArray.getAtIndex(ADDRESS, i);
                devices.add(describeDevice(a, device));
            }
            return devices;
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Physical device enumeration failed", t);
        }
    }

    private static PhysicalDevice describeDevice(Arena a, MemorySegment device) throws Throwable {
        MemorySegment props = a.allocate(StructLayouts.VK_PHYSICAL_DEVICE_PROPERTIES);
        VulkanFFM.vkGetPhysicalDeviceProperties.invokeExact((MemorySegment) device, (MemorySegment) props);

        int apiVersion = (int) StructLayouts.VK_PDP_apiVersion.get(props, 0L);
        int driverVersion = (int) StructLayouts.VK_PDP_driverVersion.get(props, 0L);
        int vendorId = (int) StructLayouts.VK_PDP_vendorID.get(props, 0L);
        int deviceId = (int) StructLayouts.VK_PDP_deviceID.get(props, 0L);
        int deviceType = (int) StructLayouts.VK_PDP_deviceType.get(props, 0L);

        MemorySegment nameSeg = props.asSlice(
                StructLayouts.VK_PDP_deviceName_OFFSET, VulkanConstants.VK_MAX_PHYSICAL_DEVICE_NAME_SIZE);
        String name = nameSeg.getString(0);

        List<QueueFamily> families = describeQueueFamilies(a, device);

        return new PhysicalDevice(device, name, vendorId, deviceId, deviceType, apiVersion, driverVersion, families);
    }

    private static List<QueueFamily> describeQueueFamilies(Arena a, MemorySegment device) throws Throwable {
        MemorySegment pCount = a.allocate(JAVA_INT);
        VulkanFFM.vkGetPhysicalDeviceQueueFamilyProperties.invokeExact(
                (MemorySegment) device, (MemorySegment) pCount, (MemorySegment) MemorySegment.NULL);
        int count = pCount.get(JAVA_INT, 0);

        List<QueueFamily> result = new ArrayList<>(count);
        if (count == 0) return result;

        MemorySegment arr = a.allocate(StructLayouts.VK_QUEUE_FAMILY_PROPERTIES, count);
        VulkanFFM.vkGetPhysicalDeviceQueueFamilyProperties.invokeExact(
                (MemorySegment) device, (MemorySegment) pCount, (MemorySegment) arr);

        long stride = StructLayouts.VK_QUEUE_FAMILY_PROPERTIES.byteSize();
        for (int i = 0; i < count; i++) {
            MemorySegment elem = arr.asSlice(i * stride, stride);
            int flags = (int) StructLayouts.VK_QFP_queueFlags.get(elem, 0L);
            int queueCount = (int) StructLayouts.VK_QFP_queueCount.get(elem, 0L);
            int tsBits = (int) StructLayouts.VK_QFP_timestampValidBits.get(elem, 0L);
            result.add(new QueueFamily(i, queueCount, flags, tsBits));
        }
        return result;
    }
}
