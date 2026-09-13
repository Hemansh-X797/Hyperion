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

    // --- Buffer usage flags ---
    public static final int VK_BUFFER_USAGE_TRANSFER_SRC_BIT = 0x00000001;
    public static final int VK_BUFFER_USAGE_TRANSFER_DST_BIT = 0x00000002;
    public static final int VK_BUFFER_USAGE_STORAGE_BUFFER_BIT = 0x00000020;

    // --- Sharing mode ---
    public static final int VK_SHARING_MODE_EXCLUSIVE = 0;

    // --- Memory property flags ---
    public static final int VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT = 0x00000002;
    public static final int VK_MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x00000004;

    // --- Descriptor types ---
    public static final int VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 7;

    // --- Shader stage flags ---
    public static final int VK_SHADER_STAGE_COMPUTE_BIT = 0x00000020;

    // --- Pipeline bind point ---
    public static final int VK_PIPELINE_BIND_POINT_COMPUTE = 1;

    // --- Command pool create flags ---
    public static final int VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT = 0x00000002;

    // --- Command buffer level ---
    public static final int VK_COMMAND_BUFFER_LEVEL_PRIMARY = 0;

    // --- Command buffer usage flags ---
    public static final int VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT = 0x00000001;

    // --- More structure types (extends the set from Phase 0) ---
    public static final int VK_STRUCTURE_TYPE_SUBMIT_INFO = 4;
    public static final int VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO = 5;
    public static final int VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO = 12;
    public static final int VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO = 33;
    public static final int VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO = 34;
    public static final int VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET = 35;
    public static final int VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO = 32;
    public static final int VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO = 18;
    public static final int VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO = 29;
    public static final int VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO = 30;
    public static final int VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO = 16;
    public static final int VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO = 39;
    public static final int VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO = 40;
    public static final int VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO = 42;

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
