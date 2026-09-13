package io.hyperion.runtime.vulkan.ffm;

import java.lang.foreign.*;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.*;
import static java.lang.foreign.MemoryLayout.PathElement.groupElement;

/**
 * Struct layouts for the logical-device / buffer / descriptor / compute-
 * pipeline path (Phase 5), built with the same explicit-padding methodology
 * validated in Phase 0's {@link StructLayouts} (structLayout() does not
 * auto-insert alignment padding — every pad byte below was computed by
 * hand against the real x86_64 System-V ABI and then empirically confirmed
 * via byteSize() checks, see Phase5Demo/README).
 *
 * All Vulkan handles (dispatchable and non-dispatchable) are 8 bytes on a
 * 64-bit build, so every handle field uses {@code ADDRESS}.
 */
public final class DeviceStructLayouts {
    private DeviceStructLayouts() {}

    // ---------------- VkDeviceQueueCreateInfo ----------------
    public static final StructLayout VK_DEVICE_QUEUE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("queueFamilyIndex"),
            JAVA_INT.withName("queueCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueuePriorities")
    ).withName("VkDeviceQueueCreateInfo");
    public static final VarHandle DQCI_sType = VK_DEVICE_QUEUE_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle DQCI_pNext = VK_DEVICE_QUEUE_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle DQCI_flags = VK_DEVICE_QUEUE_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle DQCI_queueFamilyIndex = VK_DEVICE_QUEUE_CREATE_INFO.varHandle(groupElement("queueFamilyIndex"));
    public static final VarHandle DQCI_queueCount = VK_DEVICE_QUEUE_CREATE_INFO.varHandle(groupElement("queueCount"));
    public static final VarHandle DQCI_pQueuePriorities = VK_DEVICE_QUEUE_CREATE_INFO.varHandle(groupElement("pQueuePriorities"));

    // ---------------- VkDeviceCreateInfo ----------------
    public static final StructLayout VK_DEVICE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("queueCreateInfoCount"),
            ADDRESS.withName("pQueueCreateInfos"),
            JAVA_INT.withName("enabledLayerCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledLayerNames"),
            JAVA_INT.withName("enabledExtensionCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledExtensionNames"),
            ADDRESS.withName("pEnabledFeatures")
    ).withName("VkDeviceCreateInfo");
    public static final VarHandle DCI_sType = VK_DEVICE_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle DCI_pNext = VK_DEVICE_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle DCI_flags = VK_DEVICE_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle DCI_queueCreateInfoCount = VK_DEVICE_CREATE_INFO.varHandle(groupElement("queueCreateInfoCount"));
    public static final VarHandle DCI_pQueueCreateInfos = VK_DEVICE_CREATE_INFO.varHandle(groupElement("pQueueCreateInfos"));
    public static final VarHandle DCI_enabledLayerCount = VK_DEVICE_CREATE_INFO.varHandle(groupElement("enabledLayerCount"));
    public static final VarHandle DCI_ppEnabledLayerNames = VK_DEVICE_CREATE_INFO.varHandle(groupElement("ppEnabledLayerNames"));
    public static final VarHandle DCI_enabledExtensionCount = VK_DEVICE_CREATE_INFO.varHandle(groupElement("enabledExtensionCount"));
    public static final VarHandle DCI_ppEnabledExtensionNames = VK_DEVICE_CREATE_INFO.varHandle(groupElement("ppEnabledExtensionNames"));
    public static final VarHandle DCI_pEnabledFeatures = VK_DEVICE_CREATE_INFO.varHandle(groupElement("pEnabledFeatures"));

    // ---------------- VkBufferCreateInfo ----------------
    public static final StructLayout VK_BUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("size"),
            JAVA_INT.withName("usage"),
            JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices")
    ).withName("VkBufferCreateInfo");
    public static final VarHandle BCI_sType = VK_BUFFER_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle BCI_pNext = VK_BUFFER_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle BCI_flags = VK_BUFFER_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle BCI_size = VK_BUFFER_CREATE_INFO.varHandle(groupElement("size"));
    public static final VarHandle BCI_usage = VK_BUFFER_CREATE_INFO.varHandle(groupElement("usage"));
    public static final VarHandle BCI_sharingMode = VK_BUFFER_CREATE_INFO.varHandle(groupElement("sharingMode"));
    public static final VarHandle BCI_queueFamilyIndexCount = VK_BUFFER_CREATE_INFO.varHandle(groupElement("queueFamilyIndexCount"));
    public static final VarHandle BCI_pQueueFamilyIndices = VK_BUFFER_CREATE_INFO.varHandle(groupElement("pQueueFamilyIndices"));

    // ---------------- VkMemoryRequirements ----------------
    public static final StructLayout VK_MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"),
            JAVA_LONG.withName("alignment"),
            JAVA_INT.withName("memoryTypeBits"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryRequirements");
    public static final VarHandle MR_size = VK_MEMORY_REQUIREMENTS.varHandle(groupElement("size"));
    public static final VarHandle MR_alignment = VK_MEMORY_REQUIREMENTS.varHandle(groupElement("alignment"));
    public static final VarHandle MR_memoryTypeBits = VK_MEMORY_REQUIREMENTS.varHandle(groupElement("memoryTypeBits"));

    // ---------------- VkMemoryAllocateInfo ----------------
    public static final StructLayout VK_MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_LONG.withName("allocationSize"),
            JAVA_INT.withName("memoryTypeIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryAllocateInfo");
    public static final VarHandle MAI_sType = VK_MEMORY_ALLOCATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle MAI_pNext = VK_MEMORY_ALLOCATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle MAI_allocationSize = VK_MEMORY_ALLOCATE_INFO.varHandle(groupElement("allocationSize"));
    public static final VarHandle MAI_memoryTypeIndex = VK_MEMORY_ALLOCATE_INFO.varHandle(groupElement("memoryTypeIndex"));

    // ---------------- VkPhysicalDeviceMemoryProperties ----------------
    public static final int VK_MAX_MEMORY_TYPES = 32;
    public static final int VK_MAX_MEMORY_HEAPS = 16;

    public static final StructLayout VK_MEMORY_TYPE = MemoryLayout.structLayout(
            JAVA_INT.withName("propertyFlags"),
            JAVA_INT.withName("heapIndex")
    ).withName("VkMemoryType");
    public static final VarHandle MT_propertyFlags = VK_MEMORY_TYPE.varHandle(groupElement("propertyFlags"));
    public static final VarHandle MT_heapIndex = VK_MEMORY_TYPE.varHandle(groupElement("heapIndex"));

    public static final StructLayout VK_MEMORY_HEAP = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryHeap");

    public static final StructLayout VK_PHYSICAL_DEVICE_MEMORY_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("memoryTypeCount"),
            MemoryLayout.sequenceLayout(VK_MAX_MEMORY_TYPES, VK_MEMORY_TYPE).withName("memoryTypes"),
            JAVA_INT.withName("memoryHeapCount"),
            MemoryLayout.sequenceLayout(VK_MAX_MEMORY_HEAPS, VK_MEMORY_HEAP).withName("memoryHeaps")
    ).withName("VkPhysicalDeviceMemoryProperties");
    public static final VarHandle PDMP_memoryTypeCount = VK_PHYSICAL_DEVICE_MEMORY_PROPERTIES.varHandle(groupElement("memoryTypeCount"));
    public static final VarHandle PDMP_memoryHeapCount = VK_PHYSICAL_DEVICE_MEMORY_PROPERTIES.varHandle(groupElement("memoryHeapCount"));
    public static final long PDMP_memoryTypes_OFFSET = VK_PHYSICAL_DEVICE_MEMORY_PROPERTIES.byteOffset(groupElement("memoryTypes"));

    // ---------------- VkDescriptorSetLayoutBinding ----------------
    public static final StructLayout VK_DESCRIPTOR_SET_LAYOUT_BINDING = MemoryLayout.structLayout(
            JAVA_INT.withName("binding"),
            JAVA_INT.withName("descriptorType"),
            JAVA_INT.withName("descriptorCount"),
            JAVA_INT.withName("stageFlags"),
            ADDRESS.withName("pImmutableSamplers")
    ).withName("VkDescriptorSetLayoutBinding");
    public static final VarHandle DSLB_binding = VK_DESCRIPTOR_SET_LAYOUT_BINDING.varHandle(groupElement("binding"));
    public static final VarHandle DSLB_descriptorType = VK_DESCRIPTOR_SET_LAYOUT_BINDING.varHandle(groupElement("descriptorType"));
    public static final VarHandle DSLB_descriptorCount = VK_DESCRIPTOR_SET_LAYOUT_BINDING.varHandle(groupElement("descriptorCount"));
    public static final VarHandle DSLB_stageFlags = VK_DESCRIPTOR_SET_LAYOUT_BINDING.varHandle(groupElement("stageFlags"));
    public static final VarHandle DSLB_pImmutableSamplers = VK_DESCRIPTOR_SET_LAYOUT_BINDING.varHandle(groupElement("pImmutableSamplers"));

    // ---------------- VkDescriptorSetLayoutCreateInfo ----------------
    public static final StructLayout VK_DESCRIPTOR_SET_LAYOUT_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("bindingCount"),
            ADDRESS.withName("pBindings")
    ).withName("VkDescriptorSetLayoutCreateInfo");
    public static final VarHandle DSLCI_sType = VK_DESCRIPTOR_SET_LAYOUT_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle DSLCI_pNext = VK_DESCRIPTOR_SET_LAYOUT_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle DSLCI_flags = VK_DESCRIPTOR_SET_LAYOUT_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle DSLCI_bindingCount = VK_DESCRIPTOR_SET_LAYOUT_CREATE_INFO.varHandle(groupElement("bindingCount"));
    public static final VarHandle DSLCI_pBindings = VK_DESCRIPTOR_SET_LAYOUT_CREATE_INFO.varHandle(groupElement("pBindings"));

    // ---------------- VkPipelineLayoutCreateInfo ----------------
    public static final StructLayout VK_PIPELINE_LAYOUT_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("setLayoutCount"),
            ADDRESS.withName("pSetLayouts"),
            JAVA_INT.withName("pushConstantRangeCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pPushConstantRanges")
    ).withName("VkPipelineLayoutCreateInfo");
    public static final VarHandle PLCI_sType = VK_PIPELINE_LAYOUT_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle PLCI_pNext = VK_PIPELINE_LAYOUT_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle PLCI_flags = VK_PIPELINE_LAYOUT_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle PLCI_setLayoutCount = VK_PIPELINE_LAYOUT_CREATE_INFO.varHandle(groupElement("setLayoutCount"));
    public static final VarHandle PLCI_pSetLayouts = VK_PIPELINE_LAYOUT_CREATE_INFO.varHandle(groupElement("pSetLayouts"));
    public static final VarHandle PLCI_pushConstantRangeCount = VK_PIPELINE_LAYOUT_CREATE_INFO.varHandle(groupElement("pushConstantRangeCount"));
    public static final VarHandle PLCI_pPushConstantRanges = VK_PIPELINE_LAYOUT_CREATE_INFO.varHandle(groupElement("pPushConstantRanges"));

    // ---------------- VkShaderModuleCreateInfo ----------------
    public static final StructLayout VK_SHADER_MODULE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("codeSize"),
            ADDRESS.withName("pCode")
    ).withName("VkShaderModuleCreateInfo");
    public static final VarHandle SMCI_sType = VK_SHADER_MODULE_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle SMCI_pNext = VK_SHADER_MODULE_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle SMCI_flags = VK_SHADER_MODULE_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle SMCI_codeSize = VK_SHADER_MODULE_CREATE_INFO.varHandle(groupElement("codeSize"));
    public static final VarHandle SMCI_pCode = VK_SHADER_MODULE_CREATE_INFO.varHandle(groupElement("pCode"));

    // ---------------- VkPipelineShaderStageCreateInfo ----------------
    public static final StructLayout VK_PIPELINE_SHADER_STAGE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("stage"),
            ADDRESS.withName("module"),
            ADDRESS.withName("pName"),
            ADDRESS.withName("pSpecializationInfo")
    ).withName("VkPipelineShaderStageCreateInfo");
    public static final VarHandle PSSCI_sType = VK_PIPELINE_SHADER_STAGE_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle PSSCI_pNext = VK_PIPELINE_SHADER_STAGE_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle PSSCI_flags = VK_PIPELINE_SHADER_STAGE_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle PSSCI_stage = VK_PIPELINE_SHADER_STAGE_CREATE_INFO.varHandle(groupElement("stage"));
    public static final VarHandle PSSCI_module = VK_PIPELINE_SHADER_STAGE_CREATE_INFO.varHandle(groupElement("module"));
    public static final VarHandle PSSCI_pName = VK_PIPELINE_SHADER_STAGE_CREATE_INFO.varHandle(groupElement("pName"));
    public static final VarHandle PSSCI_pSpecializationInfo = VK_PIPELINE_SHADER_STAGE_CREATE_INFO.varHandle(groupElement("pSpecializationInfo"));

    // ---------------- VkComputePipelineCreateInfo ----------------
    public static final StructLayout VK_COMPUTE_PIPELINE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4),
            VK_PIPELINE_SHADER_STAGE_CREATE_INFO.withName("stage"),
            ADDRESS.withName("layout"),
            ADDRESS.withName("basePipelineHandle"),
            JAVA_INT.withName("basePipelineIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkComputePipelineCreateInfo");
    public static final VarHandle CPCI_sType = VK_COMPUTE_PIPELINE_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle CPCI_pNext = VK_COMPUTE_PIPELINE_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle CPCI_flags = VK_COMPUTE_PIPELINE_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle CPCI_layout = VK_COMPUTE_PIPELINE_CREATE_INFO.varHandle(groupElement("layout"));
    public static final VarHandle CPCI_basePipelineHandle = VK_COMPUTE_PIPELINE_CREATE_INFO.varHandle(groupElement("basePipelineHandle"));
    public static final VarHandle CPCI_basePipelineIndex = VK_COMPUTE_PIPELINE_CREATE_INFO.varHandle(groupElement("basePipelineIndex"));
    public static final long CPCI_stage_OFFSET = VK_COMPUTE_PIPELINE_CREATE_INFO.byteOffset(groupElement("stage"));

    // ---------------- VkDescriptorPoolSize ----------------
    public static final StructLayout VK_DESCRIPTOR_POOL_SIZE = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            JAVA_INT.withName("descriptorCount")
    ).withName("VkDescriptorPoolSize");
    public static final VarHandle DPS_type = VK_DESCRIPTOR_POOL_SIZE.varHandle(groupElement("type"));
    public static final VarHandle DPS_descriptorCount = VK_DESCRIPTOR_POOL_SIZE.varHandle(groupElement("descriptorCount"));

    // ---------------- VkDescriptorPoolCreateInfo ----------------
    public static final StructLayout VK_DESCRIPTOR_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("maxSets"),
            JAVA_INT.withName("poolSizeCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pPoolSizes")
    ).withName("VkDescriptorPoolCreateInfo");
    public static final VarHandle DPCI_sType = VK_DESCRIPTOR_POOL_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle DPCI_pNext = VK_DESCRIPTOR_POOL_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle DPCI_flags = VK_DESCRIPTOR_POOL_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle DPCI_maxSets = VK_DESCRIPTOR_POOL_CREATE_INFO.varHandle(groupElement("maxSets"));
    public static final VarHandle DPCI_poolSizeCount = VK_DESCRIPTOR_POOL_CREATE_INFO.varHandle(groupElement("poolSizeCount"));
    public static final VarHandle DPCI_pPoolSizes = VK_DESCRIPTOR_POOL_CREATE_INFO.varHandle(groupElement("pPoolSizes"));

    // ---------------- VkDescriptorSetAllocateInfo ----------------
    public static final StructLayout VK_DESCRIPTOR_SET_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            ADDRESS.withName("descriptorPool"),
            JAVA_INT.withName("descriptorSetCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSetLayouts")
    ).withName("VkDescriptorSetAllocateInfo");
    public static final VarHandle DSAI_sType = VK_DESCRIPTOR_SET_ALLOCATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle DSAI_pNext = VK_DESCRIPTOR_SET_ALLOCATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle DSAI_descriptorPool = VK_DESCRIPTOR_SET_ALLOCATE_INFO.varHandle(groupElement("descriptorPool"));
    public static final VarHandle DSAI_descriptorSetCount = VK_DESCRIPTOR_SET_ALLOCATE_INFO.varHandle(groupElement("descriptorSetCount"));
    public static final VarHandle DSAI_pSetLayouts = VK_DESCRIPTOR_SET_ALLOCATE_INFO.varHandle(groupElement("pSetLayouts"));

    // ---------------- VkDescriptorBufferInfo ----------------
    public static final StructLayout VK_DESCRIPTOR_BUFFER_INFO = MemoryLayout.structLayout(
            ADDRESS.withName("buffer"),
            JAVA_LONG.withName("offset"),
            JAVA_LONG.withName("range")
    ).withName("VkDescriptorBufferInfo");
    public static final VarHandle DBI_buffer = VK_DESCRIPTOR_BUFFER_INFO.varHandle(groupElement("buffer"));
    public static final VarHandle DBI_offset = VK_DESCRIPTOR_BUFFER_INFO.varHandle(groupElement("offset"));
    public static final VarHandle DBI_range = VK_DESCRIPTOR_BUFFER_INFO.varHandle(groupElement("range"));

    // ---------------- VkWriteDescriptorSet ----------------
    public static final StructLayout VK_WRITE_DESCRIPTOR_SET = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            ADDRESS.withName("dstSet"),
            JAVA_INT.withName("dstBinding"),
            JAVA_INT.withName("dstArrayElement"),
            JAVA_INT.withName("descriptorCount"),
            JAVA_INT.withName("descriptorType"),
            ADDRESS.withName("pImageInfo"),
            ADDRESS.withName("pBufferInfo"),
            ADDRESS.withName("pTexelBufferView")
    ).withName("VkWriteDescriptorSet");
    public static final VarHandle WDS_sType = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("sType"));
    public static final VarHandle WDS_pNext = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("pNext"));
    public static final VarHandle WDS_dstSet = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("dstSet"));
    public static final VarHandle WDS_dstBinding = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("dstBinding"));
    public static final VarHandle WDS_dstArrayElement = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("dstArrayElement"));
    public static final VarHandle WDS_descriptorCount = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("descriptorCount"));
    public static final VarHandle WDS_descriptorType = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("descriptorType"));
    public static final VarHandle WDS_pImageInfo = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("pImageInfo"));
    public static final VarHandle WDS_pBufferInfo = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("pBufferInfo"));
    public static final VarHandle WDS_pTexelBufferView = VK_WRITE_DESCRIPTOR_SET.varHandle(groupElement("pTexelBufferView"));

    // ---------------- VkCommandPoolCreateInfo ----------------
    public static final StructLayout VK_COMMAND_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("queueFamilyIndex")
    ).withName("VkCommandPoolCreateInfo");
    public static final VarHandle CPoolCI_sType = VK_COMMAND_POOL_CREATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle CPoolCI_pNext = VK_COMMAND_POOL_CREATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle CPoolCI_flags = VK_COMMAND_POOL_CREATE_INFO.varHandle(groupElement("flags"));
    public static final VarHandle CPoolCI_queueFamilyIndex = VK_COMMAND_POOL_CREATE_INFO.varHandle(groupElement("queueFamilyIndex"));

    // ---------------- VkCommandBufferAllocateInfo ----------------
    public static final StructLayout VK_COMMAND_BUFFER_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            ADDRESS.withName("commandPool"),
            JAVA_INT.withName("level"),
            JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");
    public static final VarHandle CBAI_sType = VK_COMMAND_BUFFER_ALLOCATE_INFO.varHandle(groupElement("sType"));
    public static final VarHandle CBAI_pNext = VK_COMMAND_BUFFER_ALLOCATE_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle CBAI_commandPool = VK_COMMAND_BUFFER_ALLOCATE_INFO.varHandle(groupElement("commandPool"));
    public static final VarHandle CBAI_level = VK_COMMAND_BUFFER_ALLOCATE_INFO.varHandle(groupElement("level"));
    public static final VarHandle CBAI_commandBufferCount = VK_COMMAND_BUFFER_ALLOCATE_INFO.varHandle(groupElement("commandBufferCount"));

    // ---------------- VkCommandBufferBeginInfo ----------------
    public static final StructLayout VK_COMMAND_BUFFER_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");
    public static final VarHandle CBBI_sType = VK_COMMAND_BUFFER_BEGIN_INFO.varHandle(groupElement("sType"));
    public static final VarHandle CBBI_pNext = VK_COMMAND_BUFFER_BEGIN_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle CBBI_flags = VK_COMMAND_BUFFER_BEGIN_INFO.varHandle(groupElement("flags"));
    public static final VarHandle CBBI_pInheritanceInfo = VK_COMMAND_BUFFER_BEGIN_INFO.varHandle(groupElement("pInheritanceInfo"));

    // ---------------- VkSubmitInfo ----------------
    public static final StructLayout VK_SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"),
            JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"),
            JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")
    ).withName("VkSubmitInfo");
    public static final VarHandle SI_sType = VK_SUBMIT_INFO.varHandle(groupElement("sType"));
    public static final VarHandle SI_pNext = VK_SUBMIT_INFO.varHandle(groupElement("pNext"));
    public static final VarHandle SI_waitSemaphoreCount = VK_SUBMIT_INFO.varHandle(groupElement("waitSemaphoreCount"));
    public static final VarHandle SI_pWaitSemaphores = VK_SUBMIT_INFO.varHandle(groupElement("pWaitSemaphores"));
    public static final VarHandle SI_pWaitDstStageMask = VK_SUBMIT_INFO.varHandle(groupElement("pWaitDstStageMask"));
    public static final VarHandle SI_commandBufferCount = VK_SUBMIT_INFO.varHandle(groupElement("commandBufferCount"));
    public static final VarHandle SI_pCommandBuffers = VK_SUBMIT_INFO.varHandle(groupElement("pCommandBuffers"));
    public static final VarHandle SI_signalSemaphoreCount = VK_SUBMIT_INFO.varHandle(groupElement("signalSemaphoreCount"));
    public static final VarHandle SI_pSignalSemaphores = VK_SUBMIT_INFO.varHandle(groupElement("pSignalSemaphores"));
}
