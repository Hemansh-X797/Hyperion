package io.hyperion.runtime.vulkan.ffm;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * Exact x86_64 System-V ABI struct layouts for the Vulkan 1.3 core structs
 * needed for instance creation and physical device / queue enumeration.
 *
 * We deliberately use {@link MemoryLayout#structLayout(MemoryLayout...)} with
 * correctly-aligned member layouts rather than hand-computed byte offsets.
 * The Panama struct layout algorithm inserts inter-member padding based on
 * each member's natural alignment and pads total size to the alignment of
 * the widest member — which is exactly what a C compiler does for a struct
 * with no explicit packing pragma. Vulkan's headers use no packing pragmas,
 * so this reproduces the real driver ABI precisely. This is the same
 * technique used in the JDK's own java.lang.foreign Panama samples and in
 * production bindings (e.g. jextract-generated code).
 */
public final class StructLayouts {

    private StructLayouts() {}

    // ---------------------------------------------------------------
    // VkApplicationInfo  (Vulkan spec §4.1)
    // ---------------------------------------------------------------
    public static final StructLayout VK_APPLICATION_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            ADDRESS.withName("pApplicationName"),
            JAVA_INT.withName("applicationVersion"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pEngineName"),
            JAVA_INT.withName("engineVersion"),
            JAVA_INT.withName("apiVersion")
    ).withName("VkApplicationInfo");

    public static final VarHandle VK_APP_INFO_sType =
            VK_APPLICATION_INFO.varHandle(groupElement("sType"));
    public static final VarHandle VK_APP_INFO_pNext =
            VK_APPLICATION_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle VK_APP_INFO_pApplicationName =
            VK_APPLICATION_INFO.varHandle(groupElement("pApplicationName"));
    public static final VarHandle VK_APP_INFO_applicationVersion =
            VK_APPLICATION_INFO.varHandle(groupElement("applicationVersion"));
    public static final VarHandle VK_APP_INFO_pEngineName =
            VK_APPLICATION_INFO.varHandle(groupElement("pEngineName"));
    public static final VarHandle VK_APP_INFO_engineVersion =
            VK_APPLICATION_INFO.varHandle(groupElement("engineVersion"));
    public static final VarHandle VK_APP_INFO_apiVersion =
            VK_APPLICATION_INFO.varHandle(groupElement("apiVersion"));

    // ---------------------------------------------------------------
    // VkInstanceCreateInfo (Vulkan spec §4.1)
    // ---------------------------------------------------------------
    public static final StructLayout VK_INSTANCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pApplicationInfo"),
            JAVA_INT.withName("enabledLayerCount"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledLayerNames"),
            JAVA_INT.withName("enabledExtensionCount"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledExtensionNames")
    ).withName("VkInstanceCreateInfo");

    public static final VarHandle VK_ICI_sType =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle VK_ICI_pNext =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle VK_ICI_flags =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle VK_ICI_pApplicationInfo =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("pApplicationInfo"));
    public static final VarHandle VK_ICI_enabledLayerCount =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("enabledLayerCount"));
    public static final VarHandle VK_ICI_ppEnabledLayerNames =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("ppEnabledLayerNames"));
    public static final VarHandle VK_ICI_enabledExtensionCount =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("enabledExtensionCount"));
    public static final VarHandle VK_ICI_ppEnabledExtensionNames =
            VK_INSTANCE_CREATE_INFO.varHandle(groupElement("ppEnabledExtensionNames"));

    // ---------------------------------------------------------------
    // VkExtent3D
    // ---------------------------------------------------------------
    public static final StructLayout VK_EXTENT_3D = MemoryLayout.structLayout(
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("depth")
    ).withName("VkExtent3D");

    // ---------------------------------------------------------------
    // VkQueueFamilyProperties (Vulkan spec §5.2)
    // ---------------------------------------------------------------
    public static final StructLayout VK_QUEUE_FAMILY_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("queueFlags"),
            JAVA_INT.withName("queueCount"),
            JAVA_INT.withName("timestampValidBits"),
            VK_EXTENT_3D.withName("minImageTransferGranularity")
    ).withName("VkQueueFamilyProperties");

    public static final VarHandle VK_QFP_queueFlags =
            VK_QUEUE_FAMILY_PROPERTIES.varHandle(groupElement("queueFlags"));
    public static final VarHandle VK_QFP_queueCount =
            VK_QUEUE_FAMILY_PROPERTIES.varHandle(groupElement("queueCount"));
    public static final VarHandle VK_QFP_timestampValidBits =
            VK_QUEUE_FAMILY_PROPERTIES.varHandle(groupElement("timestampValidBits"));

    // ---------------------------------------------------------------
    // VkPhysicalDeviceSparseProperties (Vulkan spec §41.2, 5x VkBool32)
    // ---------------------------------------------------------------
    public static final StructLayout VK_PHYSICAL_DEVICE_SPARSE_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("residencyStandard2DBlockShape"),
            JAVA_INT.withName("residencyStandard2DMultisampleBlockShape"),
            JAVA_INT.withName("residencyStandard3DBlockShape"),
            JAVA_INT.withName("residencyAlignedMipSize"),
            JAVA_INT.withName("residencyNonResidentStrict")
    ).withName("VkPhysicalDeviceSparseProperties");

    // ---------------------------------------------------------------
    // VkPhysicalDeviceLimits (Vulkan spec §41.2) — full 106-field struct.
    // Reproduced field-for-field, in header order, so structLayout's
    // automatic alignment/padding reconstructs the exact driver ABI.
    // ---------------------------------------------------------------
    public static final StructLayout VK_PHYSICAL_DEVICE_LIMITS = MemoryLayout.structLayout(
            JAVA_INT.withName("maxImageDimension1D"),
            JAVA_INT.withName("maxImageDimension2D"),
            JAVA_INT.withName("maxImageDimension3D"),
            JAVA_INT.withName("maxImageDimensionCube"),
            JAVA_INT.withName("maxImageArrayLayers"),
            JAVA_INT.withName("maxTexelBufferElements"),
            JAVA_INT.withName("maxUniformBufferRange"),
            JAVA_INT.withName("maxStorageBufferRange"),
            JAVA_INT.withName("maxPushConstantsSize"),
            JAVA_INT.withName("maxMemoryAllocationCount"),
            JAVA_INT.withName("maxSamplerAllocationCount"),
            MemoryLayout.paddingLayout(4), // align bufferImageGranularity (u64) to 8
            JAVA_LONG.withName("bufferImageGranularity"),
            JAVA_LONG.withName("sparseAddressSpaceSize"),
            JAVA_INT.withName("maxBoundDescriptorSets"),
            JAVA_INT.withName("maxPerStageDescriptorSamplers"),
            JAVA_INT.withName("maxPerStageDescriptorUniformBuffers"),
            JAVA_INT.withName("maxPerStageDescriptorStorageBuffers"),
            JAVA_INT.withName("maxPerStageDescriptorSampledImages"),
            JAVA_INT.withName("maxPerStageDescriptorStorageImages"),
            JAVA_INT.withName("maxPerStageDescriptorInputAttachments"),
            JAVA_INT.withName("maxPerStageResources"),
            JAVA_INT.withName("maxDescriptorSetSamplers"),
            JAVA_INT.withName("maxDescriptorSetUniformBuffers"),
            JAVA_INT.withName("maxDescriptorSetUniformBuffersDynamic"),
            JAVA_INT.withName("maxDescriptorSetStorageBuffers"),
            JAVA_INT.withName("maxDescriptorSetStorageBuffersDynamic"),
            JAVA_INT.withName("maxDescriptorSetSampledImages"),
            JAVA_INT.withName("maxDescriptorSetStorageImages"),
            JAVA_INT.withName("maxDescriptorSetInputAttachments"),
            JAVA_INT.withName("maxVertexInputAttributes"),
            JAVA_INT.withName("maxVertexInputBindings"),
            JAVA_INT.withName("maxVertexInputAttributeOffset"),
            JAVA_INT.withName("maxVertexInputBindingStride"),
            JAVA_INT.withName("maxVertexOutputComponents"),
            JAVA_INT.withName("maxTessellationGenerationLevel"),
            JAVA_INT.withName("maxTessellationPatchSize"),
            JAVA_INT.withName("maxTessellationControlPerVertexInputComponents"),
            JAVA_INT.withName("maxTessellationControlPerVertexOutputComponents"),
            JAVA_INT.withName("maxTessellationControlPerPatchOutputComponents"),
            JAVA_INT.withName("maxTessellationControlTotalOutputComponents"),
            JAVA_INT.withName("maxTessellationEvaluationInputComponents"),
            JAVA_INT.withName("maxTessellationEvaluationOutputComponents"),
            JAVA_INT.withName("maxGeometryShaderInvocations"),
            JAVA_INT.withName("maxGeometryInputComponents"),
            JAVA_INT.withName("maxGeometryOutputComponents"),
            JAVA_INT.withName("maxGeometryOutputVertices"),
            JAVA_INT.withName("maxGeometryTotalOutputComponents"),
            JAVA_INT.withName("maxFragmentInputComponents"),
            JAVA_INT.withName("maxFragmentOutputAttachments"),
            JAVA_INT.withName("maxFragmentDualSrcAttachments"),
            JAVA_INT.withName("maxFragmentCombinedOutputResources"),
            JAVA_INT.withName("maxComputeSharedMemorySize"),
            MemoryLayout.sequenceLayout(3, JAVA_INT).withName("maxComputeWorkGroupCount"),
            JAVA_INT.withName("maxComputeWorkGroupInvocations"),
            MemoryLayout.sequenceLayout(3, JAVA_INT).withName("maxComputeWorkGroupSize"),
            JAVA_INT.withName("subPixelPrecisionBits"),
            JAVA_INT.withName("subTexelPrecisionBits"),
            JAVA_INT.withName("mipmapPrecisionBits"),
            JAVA_INT.withName("maxDrawIndexedIndexValue"),
            JAVA_INT.withName("maxDrawIndirectCount"),
            JAVA_FLOAT.withName("maxSamplerLodBias"),
            JAVA_FLOAT.withName("maxSamplerAnisotropy"),
            JAVA_INT.withName("maxViewports"),
            MemoryLayout.sequenceLayout(2, JAVA_INT).withName("maxViewportDimensions"),
            MemoryLayout.sequenceLayout(2, JAVA_FLOAT).withName("viewportBoundsRange"),
            JAVA_INT.withName("viewportSubPixelBits"),
            MemoryLayout.paddingLayout(4), // align size_t to 8
            JAVA_LONG.withName("minMemoryMapAlignment"),
            JAVA_LONG.withName("minTexelBufferOffsetAlignment"),
            JAVA_LONG.withName("minUniformBufferOffsetAlignment"),
            JAVA_LONG.withName("minStorageBufferOffsetAlignment"),
            JAVA_INT.withName("minTexelOffset"),
            JAVA_INT.withName("maxTexelOffset"),
            JAVA_INT.withName("minTexelGatherOffset"),
            JAVA_INT.withName("maxTexelGatherOffset"),
            JAVA_FLOAT.withName("minInterpolationOffset"),
            JAVA_FLOAT.withName("maxInterpolationOffset"),
            JAVA_INT.withName("subPixelInterpolationOffsetBits"),
            JAVA_INT.withName("maxFramebufferWidth"),
            JAVA_INT.withName("maxFramebufferHeight"),
            JAVA_INT.withName("maxFramebufferLayers"),
            JAVA_INT.withName("framebufferColorSampleCounts"),
            JAVA_INT.withName("framebufferDepthSampleCounts"),
            JAVA_INT.withName("framebufferStencilSampleCounts"),
            JAVA_INT.withName("framebufferNoAttachmentsSampleCounts"),
            JAVA_INT.withName("maxColorAttachments"),
            JAVA_INT.withName("sampledImageColorSampleCounts"),
            JAVA_INT.withName("sampledImageIntegerSampleCounts"),
            JAVA_INT.withName("sampledImageDepthSampleCounts"),
            JAVA_INT.withName("sampledImageStencilSampleCounts"),
            JAVA_INT.withName("storageImageSampleCounts"),
            JAVA_INT.withName("maxSampleMaskWords"),
            JAVA_INT.withName("timestampComputeAndGraphics"),
            JAVA_FLOAT.withName("timestampPeriod"),
            JAVA_INT.withName("maxClipDistances"),
            JAVA_INT.withName("maxCullDistances"),
            JAVA_INT.withName("maxCombinedClipAndCullDistances"),
            JAVA_INT.withName("discreteQueuePriorities"),
            MemoryLayout.sequenceLayout(2, JAVA_FLOAT).withName("pointSizeRange"),
            MemoryLayout.sequenceLayout(2, JAVA_FLOAT).withName("lineWidthRange"),
            JAVA_FLOAT.withName("pointSizeGranularity"),
            JAVA_FLOAT.withName("lineWidthGranularity"),
            JAVA_INT.withName("strictLines"),
            JAVA_INT.withName("standardSampleLocations"),
            MemoryLayout.paddingLayout(4), // align optimalBufferCopyOffsetAlignment (u64) to 8
            JAVA_LONG.withName("optimalBufferCopyOffsetAlignment"),
            JAVA_LONG.withName("optimalBufferCopyRowPitchAlignment"),
            JAVA_LONG.withName("nonCoherentAtomSize")
    ).withName("VkPhysicalDeviceLimits");

    // ---------------------------------------------------------------
    // VkPhysicalDeviceProperties (Vulkan spec §41.2)
    // ---------------------------------------------------------------
    public static final StructLayout VK_PHYSICAL_DEVICE_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("apiVersion"),
            JAVA_INT.withName("driverVersion"),
            JAVA_INT.withName("vendorID"),
            JAVA_INT.withName("deviceID"),
            JAVA_INT.withName("deviceType"),
            MemoryLayout.sequenceLayout(VulkanConstants.VK_MAX_PHYSICAL_DEVICE_NAME_SIZE, JAVA_BYTE)
                    .withName("deviceName"),
            MemoryLayout.sequenceLayout(VulkanConstants.VK_UUID_SIZE, JAVA_BYTE)
                    .withName("pipelineCacheUUID"),
            MemoryLayout.paddingLayout(4), // align nested VkPhysicalDeviceLimits (max member align 8) to 8
            VK_PHYSICAL_DEVICE_LIMITS.withName("limits"),
            VK_PHYSICAL_DEVICE_SPARSE_PROPERTIES.withName("sparseProperties"),
            // Trailing padding: sizeof(struct) in C must be a multiple of the
            // struct's own alignment (8, from the embedded uint64_t fields in
            // VkPhysicalDeviceLimits). Without this, byteSize()==820 (not a
            // multiple of 8); a real C compiler would report 824. This only
            // matters if the struct is ever used in an array (stride must be
            // 824), so we add it defensively even though a single-struct
            // vkGetPhysicalDeviceProperties() call works correctly either way.
            MemoryLayout.paddingLayout(4)
    ).withName("VkPhysicalDeviceProperties");

    public static final VarHandle VK_PDP_apiVersion =
            VK_PHYSICAL_DEVICE_PROPERTIES.varHandle(groupElement("apiVersion"));
    public static final VarHandle VK_PDP_driverVersion =
            VK_PHYSICAL_DEVICE_PROPERTIES.varHandle(groupElement("driverVersion"));
    public static final VarHandle VK_PDP_vendorID =
            VK_PHYSICAL_DEVICE_PROPERTIES.varHandle(groupElement("vendorID"));
    public static final VarHandle VK_PDP_deviceID =
            VK_PHYSICAL_DEVICE_PROPERTIES.varHandle(groupElement("deviceID"));
    public static final VarHandle VK_PDP_deviceType =
            VK_PHYSICAL_DEVICE_PROPERTIES.varHandle(groupElement("deviceType"));

    public static final long VK_PDP_deviceName_OFFSET =
            VK_PHYSICAL_DEVICE_PROPERTIES.byteOffset(groupElement("deviceName"));
}
