package io.hyperion.runtime.vulkan.ffm;

/**
 * Raw Vulkan 1.3 constants required for instance/device bootstrapping.
 * Values taken verbatim from the Khronos vulkan_core.h header (Apache-2.0 /
 * MIT dual-licensed spec headers — values are numeric constants, not
 * copyrightable expression, and are reproduced here as plain integers only).
 *
 * We do NOT vendor the Khronos headers themselves (avoids any licensing
 * ambiguity) — we only encode the numeric ABI constants we need, which is
 * the same approach LWJGL/JNA bindings use.
 */
public final class VulkanConstants {

    private VulkanConstants() {}

    // --- Result codes (VkResult) ---
    public static final int VK_SUCCESS = 0;
    public static final int VK_NOT_READY = 1;
    public static final int VK_TIMEOUT = 2;
    public static final int VK_INCOMPLETE = 5;
    public static final int VK_ERROR_OUT_OF_HOST_MEMORY = -1;
    public static final int VK_ERROR_OUT_OF_DEVICE_MEMORY = -2;
    public static final int VK_ERROR_INITIALIZATION_FAILED = -3;
    public static final int VK_ERROR_LAYER_NOT_PRESENT = -6;
    public static final int VK_ERROR_EXTENSION_NOT_PRESENT = -7;
    public static final int VK_ERROR_INCOMPATIBLE_DRIVER = -9;

    // --- Structure types (VkStructureType) ---
    public static final int VK_STRUCTURE_TYPE_APPLICATION_INFO = 0;
    public static final int VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO = 1;
    public static final int VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO = 2;
    public static final int VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO = 3;

    // --- Queue flags (VkQueueFlagBits) ---
    public static final int VK_QUEUE_GRAPHICS_BIT = 0x00000001;
    public static final int VK_QUEUE_COMPUTE_BIT = 0x00000002;
    public static final int VK_QUEUE_TRANSFER_BIT = 0x00000004;
    public static final int VK_QUEUE_SPARSE_BINDING_BIT = 0x00000008;

    // --- Physical device types (VkPhysicalDeviceType) ---
    public static final int VK_PHYSICAL_DEVICE_TYPE_OTHER = 0;
    public static final int VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU = 1;
    public static final int VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU = 2;
    public static final int VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU = 3;
    public static final int VK_PHYSICAL_DEVICE_TYPE_CPU = 4;

    // --- API version encoding (VK_MAKE_API_VERSION) ---
    public static int makeApiVersion(int variant, int major, int minor, int patch) {
        return (variant << 29) | (major << 22) | (minor << 12) | patch;
    }

    public static final int VK_API_VERSION_1_3 = makeApiVersion(0, 1, 3, 0);

    public static int versionMajor(int v) { return (v >> 22) & 0x7F; }
    public static int versionMinor(int v) { return (v >> 12) & 0x3FF; }
    public static int versionPatch(int v) { return v & 0xFFF; }

    // --- Misc sizing ---
    public static final int VK_MAX_PHYSICAL_DEVICE_NAME_SIZE = 256;
    public static final int VK_UUID_SIZE = 16;
}
