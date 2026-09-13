package io.hyperion.runtime.vulkan.pipeline;

import io.hyperion.runtime.vulkan.device.PhysicalDeviceManager;
import io.hyperion.runtime.vulkan.ffm.VulkanConstants;
import io.hyperion.runtime.vulkan.ffm.VulkanFFM;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static io.hyperion.runtime.vulkan.ffm.DeviceStructLayouts.*;
import static io.hyperion.runtime.vulkan.ffm.VulkanConstants.*;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * End-to-end real GPU compute dispatch: creates a logical device, two
 * host-visible+host-coherent storage buffers (input params, output
 * result), a descriptor set binding them to a compute pipeline built from
 * real SPIR-V bytes (Phase 4's {@code KernelSpirvEmitter} output), records
 * and submits a single {@code dispatch(1,1,1)}, waits for completion, and
 * reads the result back — the payoff of Phase 0 (Vulkan FFM) and Phase 4
 * (SPIR-V) meeting for the first time.
 *
 * Host-visible+coherent memory is used directly (no staging buffer +
 * transfer queue) — correct and simple for the small scalar-kernel inputs
 * this phase targets; a real production path would stage through
 * device-local memory for large tensors, a natural Phase 6/8 concern.
 */
public final class ComputeDispatcher implements AutoCloseable {

    private final Arena arena = Arena.ofShared(); // must support access from any worker thread (Phase 8 work-stealing may run a dispatch() call on a different thread than the one that created this ComputeDispatcher)
    private final MemorySegment device;
    private final MemorySegment queue;
    private final int queueFamilyIndex;
    private final MemorySegment physicalDevice;

    private ComputeDispatcher(MemorySegment device, MemorySegment queue, int queueFamilyIndex, MemorySegment physicalDevice) {
        this.device = device;
        this.queue = queue;
        this.queueFamilyIndex = queueFamilyIndex;
        this.physicalDevice = physicalDevice;
    }

    public static ComputeDispatcher create(PhysicalDeviceManager.PhysicalDevice pd) {
        Arena a = Arena.ofConfined();
        try {
            int queueFamilyIndex = pd.firstComputeQueueFamily();

            MemorySegment priority = a.allocate(java.lang.foreign.ValueLayout.JAVA_FLOAT);
            priority.set(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0, 1.0f);

            MemorySegment queueCreateInfo = a.allocate(VK_DEVICE_QUEUE_CREATE_INFO);
            DQCI_sType.set(queueCreateInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
            DQCI_pNext.set(queueCreateInfo, 0L, MemorySegment.NULL);
            DQCI_flags.set(queueCreateInfo, 0L, 0);
            DQCI_queueFamilyIndex.set(queueCreateInfo, 0L, queueFamilyIndex);
            DQCI_queueCount.set(queueCreateInfo, 0L, 1);
            DQCI_pQueuePriorities.set(queueCreateInfo, 0L, priority);

            MemorySegment deviceCreateInfo = a.allocate(VK_DEVICE_CREATE_INFO);
            DCI_sType.set(deviceCreateInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            DCI_pNext.set(deviceCreateInfo, 0L, MemorySegment.NULL);
            DCI_flags.set(deviceCreateInfo, 0L, 0);
            DCI_queueCreateInfoCount.set(deviceCreateInfo, 0L, 1);
            DCI_pQueueCreateInfos.set(deviceCreateInfo, 0L, queueCreateInfo);
            DCI_enabledLayerCount.set(deviceCreateInfo, 0L, 0);
            DCI_ppEnabledLayerNames.set(deviceCreateInfo, 0L, MemorySegment.NULL);
            DCI_enabledExtensionCount.set(deviceCreateInfo, 0L, 0);
            DCI_ppEnabledExtensionNames.set(deviceCreateInfo, 0L, MemorySegment.NULL);
            DCI_pEnabledFeatures.set(deviceCreateInfo, 0L, MemorySegment.NULL);

            MemorySegment pDevice = a.allocate(ADDRESS);
            int r = (int) VulkanFFM.vkCreateDevice.invokeExact(
                    (MemorySegment) pd.handle(), (MemorySegment) deviceCreateInfo,
                    (MemorySegment) MemorySegment.NULL, (MemorySegment) pDevice);
            VulkanFFM.check(r, "vkCreateDevice");
            MemorySegment device = pDevice.get(ADDRESS, 0);

            MemorySegment pQueue = a.allocate(ADDRESS);
            VulkanFFM.vkGetDeviceQueue.invokeExact(
                    (MemorySegment) device, queueFamilyIndex, 0, (MemorySegment) pQueue);
            MemorySegment queue = pQueue.get(ADDRESS, 0);

            return new ComputeDispatcher(device, queue, queueFamilyIndex, pd.handle());
        } catch (Throwable t) {
            a.close();
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Failed to create logical device", t);
        }
    }

    /**
     * Runs one kernel: {@code inputs} are packed as raw 4-byte words (float
     * bit patterns if {@code isFloat}, else raw ints) into a readonly
     * storage buffer at binding 0; the result is read back from a
     * writeonly storage buffer at binding 1. Returns the result widened to
     * {@code double} for uniform comparison against real Java execution,
     * the same convention every prior phase's cross-checks have used.
     */
    public double dispatch(byte[] spirv, String entryPoint, double[] inputs, boolean isFloat) {
        try {
            long inputBytes = Math.max(4L, inputs.length * 4L);
            BufferAndMemory inputBuf = createHostVisibleBuffer(arena, inputBytes, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);
            BufferAndMemory outputBuf = createHostVisibleBuffer(arena, 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT);

            writeInputs(inputBuf, inputs, isFloat);

            MemorySegment setLayout = createDescriptorSetLayout(arena);
            MemorySegment pipelineLayout = createPipelineLayout(arena, setLayout);
            MemorySegment shaderModule = createShaderModule(arena, spirv);
            MemorySegment pipeline = createComputePipeline(arena, shaderModule, pipelineLayout, entryPoint);
            MemorySegment descriptorPool = createDescriptorPool(arena);
            MemorySegment descriptorSet = allocateDescriptorSet(arena, descriptorPool, setLayout);
            writeDescriptors(arena, descriptorSet, inputBuf.buffer, outputBuf.buffer);

            MemorySegment commandPool = createCommandPool(arena);
            MemorySegment commandBuffer = allocateCommandBuffer(arena, commandPool);
            recordDispatch(commandBuffer, pipeline, pipelineLayout, descriptorSet);
            submitAndWait(commandBuffer);

            double result = readOutput(outputBuf, isFloat);

            invokeVoid(VulkanFFM.vkDestroyCommandPool, device, commandPool);
            invokeVoid(VulkanFFM.vkDestroyDescriptorPool, device, descriptorPool);
            invokeVoid(VulkanFFM.vkDestroyPipeline, device, pipeline);
            invokeVoid(VulkanFFM.vkDestroyPipelineLayout, device, pipelineLayout);
            invokeVoid(VulkanFFM.vkDestroyShaderModule, device, shaderModule);
            invokeVoid(VulkanFFM.vkDestroyDescriptorSetLayout, device, setLayout);
            destroyBuffer(outputBuf);
            destroyBuffer(inputBuf);

            return result;
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Kernel dispatch failed", t);
        }
    }

    private record BufferAndMemory(MemorySegment buffer, MemorySegment memory, MemorySegment mappedPtr) {}

    private BufferAndMemory createHostVisibleBuffer(Arena a, long size, int usage) throws Throwable {
        MemorySegment bufferCreateInfo = a.allocate(VK_BUFFER_CREATE_INFO);
        BCI_sType.set(bufferCreateInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO);
        BCI_pNext.set(bufferCreateInfo, 0L, MemorySegment.NULL);
        BCI_flags.set(bufferCreateInfo, 0L, 0);
        BCI_size.set(bufferCreateInfo, 0L, size);
        BCI_usage.set(bufferCreateInfo, 0L, usage);
        BCI_sharingMode.set(bufferCreateInfo, 0L, VK_SHARING_MODE_EXCLUSIVE);
        BCI_queueFamilyIndexCount.set(bufferCreateInfo, 0L, 0);
        BCI_pQueueFamilyIndices.set(bufferCreateInfo, 0L, MemorySegment.NULL);

        MemorySegment pBuffer = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkCreateBuffer.invokeExact(
                (MemorySegment) device, (MemorySegment) bufferCreateInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pBuffer);
        VulkanFFM.check(r, "vkCreateBuffer");
        MemorySegment buffer = pBuffer.get(ADDRESS, 0);

        MemorySegment memReqs = a.allocate(VK_MEMORY_REQUIREMENTS);
        VulkanFFM.vkGetBufferMemoryRequirements.invokeExact((MemorySegment) device, (MemorySegment) buffer, (MemorySegment) memReqs);
        long allocSize = (long) MR_size.get(memReqs, 0L);
        int memTypeBits = (int) MR_memoryTypeBits.get(memReqs, 0L);

        int memTypeIndex = findMemoryType(a, memTypeBits,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

        MemorySegment allocInfo = a.allocate(VK_MEMORY_ALLOCATE_INFO);
        MAI_sType.set(allocInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
        MAI_pNext.set(allocInfo, 0L, MemorySegment.NULL);
        MAI_allocationSize.set(allocInfo, 0L, allocSize);
        MAI_memoryTypeIndex.set(allocInfo, 0L, memTypeIndex);

        MemorySegment pMemory = a.allocate(ADDRESS);
        r = (int) VulkanFFM.vkAllocateMemory.invokeExact(
                (MemorySegment) device, (MemorySegment) allocInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pMemory);
        VulkanFFM.check(r, "vkAllocateMemory");
        MemorySegment memory = pMemory.get(ADDRESS, 0);

        r = (int) VulkanFFM.vkBindBufferMemory.invokeExact((MemorySegment) device, (MemorySegment) buffer, (MemorySegment) memory, 0L);
        VulkanFFM.check(r, "vkBindBufferMemory");

        MemorySegment pData = a.allocate(ADDRESS);
        r = (int) VulkanFFM.vkMapMemory.invokeExact((MemorySegment) device, (MemorySegment) memory, 0L, size, 0, (MemorySegment) pData);
        VulkanFFM.check(r, "vkMapMemory");
        MemorySegment mapped = pData.get(ADDRESS, 0).reinterpret(size);

        return new BufferAndMemory(buffer, memory, mapped);
    }

    private void destroyBuffer(BufferAndMemory b) throws Throwable {
        VulkanFFM.vkUnmapMemory.invokeExact((MemorySegment) device, (MemorySegment) b.memory);
        invokeVoid(VulkanFFM.vkFreeMemory, device, b.memory);
        invokeVoid(VulkanFFM.vkDestroyBuffer, device, b.buffer);
    }

    private int findMemoryType(Arena a, int typeBits, int requiredProperties) throws Throwable {
        MemorySegment props = a.allocate(VK_PHYSICAL_DEVICE_MEMORY_PROPERTIES);
        VulkanFFM.vkGetPhysicalDeviceMemoryProperties.invokeExact((MemorySegment) physicalDevice, (MemorySegment) props);
        int count = (int) PDMP_memoryTypeCount.get(props, 0L);
        long stride = VK_MEMORY_TYPE.byteSize();
        for (int i = 0; i < count; i++) {
            boolean inTypeBits = (typeBits & (1 << i)) != 0;
            MemorySegment elem = props.asSlice(PDMP_memoryTypes_OFFSET + i * stride, stride);
            int flags = (int) MT_propertyFlags.get(elem, 0L);
            if (inTypeBits && (flags & requiredProperties) == requiredProperties) {
                return i;
            }
        }
        throw new IllegalStateException("No memory type found with properties " + requiredProperties
                + " among typeBits=" + Integer.toBinaryString(typeBits));
    }

    private void writeInputs(BufferAndMemory buf, double[] inputs, boolean isFloat) {
        for (int i = 0; i < inputs.length; i++) {
            if (isFloat) {
                buf.mappedPtr.setAtIndex(java.lang.foreign.ValueLayout.JAVA_FLOAT, i, (float) inputs[i]);
            } else {
                buf.mappedPtr.setAtIndex(JAVA_INT, i, (int) inputs[i]);
            }
        }
    }

    private double readOutput(BufferAndMemory buf, boolean isFloat) {
        return isFloat
                ? buf.mappedPtr.get(java.lang.foreign.ValueLayout.JAVA_FLOAT, 0)
                : buf.mappedPtr.get(JAVA_INT, 0);
    }

    private MemorySegment createDescriptorSetLayout(Arena a) throws Throwable {
        MemorySegment bindings = a.allocate(VK_DESCRIPTOR_SET_LAYOUT_BINDING, 2);
        for (int i = 0; i < 2; i++) {
            MemorySegment b = bindings.asSlice(i * VK_DESCRIPTOR_SET_LAYOUT_BINDING.byteSize(), VK_DESCRIPTOR_SET_LAYOUT_BINDING.byteSize());
            DSLB_binding.set(b, 0L, i);
            DSLB_descriptorType.set(b, 0L, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            DSLB_descriptorCount.set(b, 0L, 1);
            DSLB_stageFlags.set(b, 0L, VK_SHADER_STAGE_COMPUTE_BIT);
            DSLB_pImmutableSamplers.set(b, 0L, MemorySegment.NULL);
        }

        MemorySegment createInfo = a.allocate(VK_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
        DSLCI_sType.set(createInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
        DSLCI_pNext.set(createInfo, 0L, MemorySegment.NULL);
        DSLCI_flags.set(createInfo, 0L, 0);
        DSLCI_bindingCount.set(createInfo, 0L, 2);
        DSLCI_pBindings.set(createInfo, 0L, bindings);

        MemorySegment pLayout = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkCreateDescriptorSetLayout.invokeExact(
                (MemorySegment) device, (MemorySegment) createInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pLayout);
        VulkanFFM.check(r, "vkCreateDescriptorSetLayout");
        return pLayout.get(ADDRESS, 0);
    }

    private MemorySegment createPipelineLayout(Arena a, MemorySegment setLayout) throws Throwable {
        MemorySegment setLayouts = a.allocate(ADDRESS, 1);
        setLayouts.setAtIndex(ADDRESS, 0, setLayout);

        MemorySegment createInfo = a.allocate(VK_PIPELINE_LAYOUT_CREATE_INFO);
        PLCI_sType.set(createInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
        PLCI_pNext.set(createInfo, 0L, MemorySegment.NULL);
        PLCI_flags.set(createInfo, 0L, 0);
        PLCI_setLayoutCount.set(createInfo, 0L, 1);
        PLCI_pSetLayouts.set(createInfo, 0L, setLayouts);
        PLCI_pushConstantRangeCount.set(createInfo, 0L, 0);
        PLCI_pPushConstantRanges.set(createInfo, 0L, MemorySegment.NULL);

        MemorySegment pLayout = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkCreatePipelineLayout.invokeExact(
                (MemorySegment) device, (MemorySegment) createInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pLayout);
        VulkanFFM.check(r, "vkCreatePipelineLayout");
        return pLayout.get(ADDRESS, 0);
    }

    private MemorySegment createShaderModule(Arena a, byte[] spirv) throws Throwable {
        MemorySegment code = a.allocate(spirv.length);
        MemorySegment.copy(spirv, 0, code, java.lang.foreign.ValueLayout.JAVA_BYTE, 0, spirv.length);

        MemorySegment createInfo = a.allocate(VK_SHADER_MODULE_CREATE_INFO);
        SMCI_sType.set(createInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
        SMCI_pNext.set(createInfo, 0L, MemorySegment.NULL);
        SMCI_flags.set(createInfo, 0L, 0);
        SMCI_codeSize.set(createInfo, 0L, (long) spirv.length);
        SMCI_pCode.set(createInfo, 0L, code);

        MemorySegment pModule = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkCreateShaderModule.invokeExact(
                (MemorySegment) device, (MemorySegment) createInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pModule);
        VulkanFFM.check(r, "vkCreateShaderModule");
        return pModule.get(ADDRESS, 0);
    }

    private MemorySegment createComputePipeline(Arena a, MemorySegment shaderModule, MemorySegment pipelineLayout, String entryPoint) throws Throwable {
        MemorySegment entryPointName = a.allocateFrom(entryPoint);

        MemorySegment createInfo = a.allocate(VK_COMPUTE_PIPELINE_CREATE_INFO);
        CPCI_sType.set(createInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO);
        CPCI_pNext.set(createInfo, 0L, MemorySegment.NULL);
        CPCI_flags.set(createInfo, 0L, 0);

        MemorySegment stage = createInfo.asSlice(CPCI_stage_OFFSET, VK_PIPELINE_SHADER_STAGE_CREATE_INFO.byteSize());
        PSSCI_sType.set(stage, 0L, VulkanConstants.VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
        PSSCI_pNext.set(stage, 0L, MemorySegment.NULL);
        PSSCI_flags.set(stage, 0L, 0);
        PSSCI_stage.set(stage, 0L, VK_SHADER_STAGE_COMPUTE_BIT);
        PSSCI_module.set(stage, 0L, shaderModule);
        PSSCI_pName.set(stage, 0L, entryPointName);
        PSSCI_pSpecializationInfo.set(stage, 0L, MemorySegment.NULL);

        CPCI_layout.set(createInfo, 0L, pipelineLayout);
        CPCI_basePipelineHandle.set(createInfo, 0L, MemorySegment.NULL);
        CPCI_basePipelineIndex.set(createInfo, 0L, -1);

        MemorySegment pPipeline = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkCreateComputePipelines.invokeExact(
                (MemorySegment) device, (MemorySegment) MemorySegment.NULL, 1,
                (MemorySegment) createInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pPipeline);
        VulkanFFM.check(r, "vkCreateComputePipelines");
        return pPipeline.get(ADDRESS, 0);
    }

    private MemorySegment createDescriptorPool(Arena a) throws Throwable {
        MemorySegment poolSize = a.allocate(VK_DESCRIPTOR_POOL_SIZE);
        DPS_type.set(poolSize, 0L, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
        DPS_descriptorCount.set(poolSize, 0L, 2);

        MemorySegment createInfo = a.allocate(VK_DESCRIPTOR_POOL_CREATE_INFO);
        DPCI_sType.set(createInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
        DPCI_pNext.set(createInfo, 0L, MemorySegment.NULL);
        DPCI_flags.set(createInfo, 0L, 0);
        DPCI_maxSets.set(createInfo, 0L, 1);
        DPCI_poolSizeCount.set(createInfo, 0L, 1);
        DPCI_pPoolSizes.set(createInfo, 0L, poolSize);

        MemorySegment pPool = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkCreateDescriptorPool.invokeExact(
                (MemorySegment) device, (MemorySegment) createInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pPool);
        VulkanFFM.check(r, "vkCreateDescriptorPool");
        return pPool.get(ADDRESS, 0);
    }

    private MemorySegment allocateDescriptorSet(Arena a, MemorySegment pool, MemorySegment setLayout) throws Throwable {
        MemorySegment layouts = a.allocate(ADDRESS, 1);
        layouts.setAtIndex(ADDRESS, 0, setLayout);

        MemorySegment allocInfo = a.allocate(VK_DESCRIPTOR_SET_ALLOCATE_INFO);
        DSAI_sType.set(allocInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
        DSAI_pNext.set(allocInfo, 0L, MemorySegment.NULL);
        DSAI_descriptorPool.set(allocInfo, 0L, pool);
        DSAI_descriptorSetCount.set(allocInfo, 0L, 1);
        DSAI_pSetLayouts.set(allocInfo, 0L, layouts);

        MemorySegment pSet = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkAllocateDescriptorSets.invokeExact((MemorySegment) device, (MemorySegment) allocInfo, (MemorySegment) pSet);
        VulkanFFM.check(r, "vkAllocateDescriptorSets");
        return pSet.get(ADDRESS, 0);
    }

    private void writeDescriptors(Arena a, MemorySegment descriptorSet, MemorySegment inputBuffer, MemorySegment outputBuffer) throws Throwable {
        long VK_WHOLE_SIZE = -1L; // per spec: ~0ULL means "to the end of the buffer"

        MemorySegment bufferInfos = a.allocate(VK_DESCRIPTOR_BUFFER_INFO, 2);
        MemorySegment inInfo = bufferInfos.asSlice(0, VK_DESCRIPTOR_BUFFER_INFO.byteSize());
        DBI_buffer.set(inInfo, 0L, inputBuffer);
        DBI_offset.set(inInfo, 0L, 0L);
        DBI_range.set(inInfo, 0L, VK_WHOLE_SIZE);

        MemorySegment outInfo = bufferInfos.asSlice(VK_DESCRIPTOR_BUFFER_INFO.byteSize(), VK_DESCRIPTOR_BUFFER_INFO.byteSize());
        DBI_buffer.set(outInfo, 0L, outputBuffer);
        DBI_offset.set(outInfo, 0L, 0L);
        DBI_range.set(outInfo, 0L, VK_WHOLE_SIZE);

        MemorySegment writes = a.allocate(VK_WRITE_DESCRIPTOR_SET, 2);
        for (int i = 0; i < 2; i++) {
            MemorySegment w = writes.asSlice(i * VK_WRITE_DESCRIPTOR_SET.byteSize(), VK_WRITE_DESCRIPTOR_SET.byteSize());
            WDS_sType.set(w, 0L, VulkanConstants.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            WDS_pNext.set(w, 0L, MemorySegment.NULL);
            WDS_dstSet.set(w, 0L, descriptorSet);
            WDS_dstBinding.set(w, 0L, i);
            WDS_dstArrayElement.set(w, 0L, 0);
            WDS_descriptorCount.set(w, 0L, 1);
            WDS_descriptorType.set(w, 0L, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            WDS_pImageInfo.set(w, 0L, MemorySegment.NULL);
            WDS_pBufferInfo.set(w, 0L, bufferInfos.asSlice(i * VK_DESCRIPTOR_BUFFER_INFO.byteSize(), VK_DESCRIPTOR_BUFFER_INFO.byteSize()));
            WDS_pTexelBufferView.set(w, 0L, MemorySegment.NULL);
        }

        VulkanFFM.vkUpdateDescriptorSets.invokeExact((MemorySegment) device, 2, (MemorySegment) writes, 0, (MemorySegment) MemorySegment.NULL);
    }

    private MemorySegment createCommandPool(Arena a) throws Throwable {
        MemorySegment createInfo = a.allocate(VK_COMMAND_POOL_CREATE_INFO);
        CPoolCI_sType.set(createInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        CPoolCI_pNext.set(createInfo, 0L, MemorySegment.NULL);
        CPoolCI_flags.set(createInfo, 0L, VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
        CPoolCI_queueFamilyIndex.set(createInfo, 0L, queueFamilyIndex);

        MemorySegment pPool = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkCreateCommandPool.invokeExact(
                (MemorySegment) device, (MemorySegment) createInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pPool);
        VulkanFFM.check(r, "vkCreateCommandPool");
        return pPool.get(ADDRESS, 0);
    }

    private MemorySegment allocateCommandBuffer(Arena a, MemorySegment pool) throws Throwable {
        MemorySegment allocInfo = a.allocate(VK_COMMAND_BUFFER_ALLOCATE_INFO);
        CBAI_sType.set(allocInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        CBAI_pNext.set(allocInfo, 0L, MemorySegment.NULL);
        CBAI_commandPool.set(allocInfo, 0L, pool);
        CBAI_level.set(allocInfo, 0L, VK_COMMAND_BUFFER_LEVEL_PRIMARY);
        CBAI_commandBufferCount.set(allocInfo, 0L, 1);

        MemorySegment pCmdBuf = a.allocate(ADDRESS);
        int r = (int) VulkanFFM.vkAllocateCommandBuffers.invokeExact((MemorySegment) device, (MemorySegment) allocInfo, (MemorySegment) pCmdBuf);
        VulkanFFM.check(r, "vkAllocateCommandBuffers");
        return pCmdBuf.get(ADDRESS, 0);
    }

    private void recordDispatch(MemorySegment cmdBuf, MemorySegment pipeline, MemorySegment pipelineLayout, MemorySegment descriptorSet) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment beginInfo = a.allocate(VK_COMMAND_BUFFER_BEGIN_INFO);
            CBBI_sType.set(beginInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            CBBI_pNext.set(beginInfo, 0L, MemorySegment.NULL);
            CBBI_flags.set(beginInfo, 0L, VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            CBBI_pInheritanceInfo.set(beginInfo, 0L, MemorySegment.NULL);

            int r = (int) VulkanFFM.vkBeginCommandBuffer.invokeExact((MemorySegment) cmdBuf, (MemorySegment) beginInfo);
            VulkanFFM.check(r, "vkBeginCommandBuffer");

            VulkanFFM.vkCmdBindPipeline.invokeExact((MemorySegment) cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, (MemorySegment) pipeline);

            MemorySegment sets = a.allocate(ADDRESS, 1);
            sets.setAtIndex(ADDRESS, 0, descriptorSet);
            VulkanFFM.vkCmdBindDescriptorSets.invokeExact(
                    (MemorySegment) cmdBuf, VK_PIPELINE_BIND_POINT_COMPUTE, (MemorySegment) pipelineLayout,
                    0, 1, (MemorySegment) sets, 0, (MemorySegment) MemorySegment.NULL);

            VulkanFFM.vkCmdDispatch.invokeExact((MemorySegment) cmdBuf, 1, 1, 1);

            r = (int) VulkanFFM.vkEndCommandBuffer.invokeExact((MemorySegment) cmdBuf);
            VulkanFFM.check(r, "vkEndCommandBuffer");
        }
    }

    private void submitAndWait(MemorySegment cmdBuf) throws Throwable {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment cmdBufs = a.allocate(ADDRESS, 1);
            cmdBufs.setAtIndex(ADDRESS, 0, cmdBuf);

            MemorySegment submitInfo = a.allocate(VK_SUBMIT_INFO);
            SI_sType.set(submitInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_SUBMIT_INFO);
            SI_pNext.set(submitInfo, 0L, MemorySegment.NULL);
            SI_waitSemaphoreCount.set(submitInfo, 0L, 0);
            SI_pWaitSemaphores.set(submitInfo, 0L, MemorySegment.NULL);
            SI_pWaitDstStageMask.set(submitInfo, 0L, MemorySegment.NULL);
            SI_commandBufferCount.set(submitInfo, 0L, 1);
            SI_pCommandBuffers.set(submitInfo, 0L, cmdBufs);
            SI_signalSemaphoreCount.set(submitInfo, 0L, 0);
            SI_pSignalSemaphores.set(submitInfo, 0L, MemorySegment.NULL);

            int r = (int) VulkanFFM.vkQueueSubmit.invokeExact((MemorySegment) queue, 1, (MemorySegment) submitInfo, (MemorySegment) MemorySegment.NULL);
            VulkanFFM.check(r, "vkQueueSubmit");
            r = (int) VulkanFFM.vkQueueWaitIdle.invokeExact((MemorySegment) queue);
            VulkanFFM.check(r, "vkQueueWaitIdle");
        }
    }

    private static void invokeVoid(java.lang.invoke.MethodHandle h, MemorySegment device, MemorySegment obj) throws Throwable {
        h.invokeExact((MemorySegment) device, (MemorySegment) obj, (MemorySegment) MemorySegment.NULL);
    }

    @Override
    public void close() {
        try {
            VulkanFFM.vkDeviceWaitIdle.invokeExact((MemorySegment) device);
        } catch (Throwable ignored) {
        }
        try {
            VulkanFFM.vkDestroyDevice.invokeExact((MemorySegment) device, (MemorySegment) MemorySegment.NULL);
        } catch (Throwable ignored) {
        } finally {
            arena.close();
        }
    }
}
