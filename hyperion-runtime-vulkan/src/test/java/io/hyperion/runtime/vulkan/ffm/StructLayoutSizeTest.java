package io.hyperion.runtime.vulkan.ffm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks in the exact byte sizes of every Vulkan struct layout we hand-derive
 * in {@link StructLayouts}. These values were cross-checked against a
 * from-scratch C-ABI offset simulation (System V x86_64: natural alignment,
 * no packing) and confirmed by successfully round-tripping real data through
 * the actual Vulkan loader (libvulkan.so.1) in Phase0Demo. If any of these
 * assertions ever fail after a struct edit, the ABI is broken — do not
 * "fix" the test, fix the layout.
 */
class StructLayoutSizeTest {

    @Test
    void vkApplicationInfo_is48Bytes() {
        assertEquals(48, StructLayouts.VK_APPLICATION_INFO.byteSize());
    }

    @Test
    void vkInstanceCreateInfo_is64Bytes() {
        assertEquals(64, StructLayouts.VK_INSTANCE_CREATE_INFO.byteSize());
    }

    @Test
    void vkQueueFamilyProperties_is24Bytes() {
        assertEquals(24, StructLayouts.VK_QUEUE_FAMILY_PROPERTIES.byteSize());
    }

    @Test
    void vkPhysicalDeviceLimits_is504Bytes() {
        assertEquals(504, StructLayouts.VK_PHYSICAL_DEVICE_LIMITS.byteSize());
    }

    @Test
    void vkPhysicalDeviceProperties_is824Bytes() {
        // 5 x u32 (20) + deviceName[256] + pipelineCacheUUID[16] + 4 pad
        // + limits(504) + sparseProperties(20) + 4 trailing pad = 824,
        // matching real C sizeof(VkPhysicalDeviceProperties) on LP64.
        assertEquals(824, StructLayouts.VK_PHYSICAL_DEVICE_PROPERTIES.byteSize());
    }

    @Test
    void deviceNameOffset_matchesRealAbi() {
        // apiVersion, driverVersion, vendorID, deviceID, deviceType = 5 * 4 bytes
        assertEquals(20, StructLayouts.VK_PDP_deviceName_OFFSET);
    }
}
