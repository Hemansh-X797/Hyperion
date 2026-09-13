package io.hyperion.runtime.vulkan.ffm;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.*;

/**
 * Direct native bindings to the Vulkan loader (libvulkan.so.1 / vulkan-1.dll)
 * via java.lang.foreign — no JNI, no LWJGL, no generated glue code.
 *
 * Each vk* entry point is bound exactly once, lazily, as a MethodHandle with
 * an explicit FunctionDescriptor matching the real C signature. Linker.Option
 * .isTrivial() is intentionally NOT used for functions that can block or
 * call back into the JVM allocator; all these calls are short, non-blocking
 * loader/query functions so we keep default linker options for correctness.
 */
public final class VulkanFFM {

    private VulkanFFM() {}

    public static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup VULKAN_LOOKUP = resolveVulkanLibrary();

    private static SymbolLookup resolveVulkanLibrary() {
        String os = System.getProperty("os.name").toLowerCase();
        RuntimeException last = null;
        String[] candidates;
        if (os.contains("win")) {
            candidates = new String[] { "vulkan-1" };
        } else if (os.contains("mac")) {
            candidates = new String[] { "vulkan", "MoltenVK" };
        } else {
            // Linux: try unversioned first, then the real soname the loader ships as.
            candidates = new String[] { "vulkan", "libvulkan.so.1" };
        }
        for (String name : candidates) {
            try {
                return SymbolLookup.libraryLookup(name, Arena.global());
            } catch (RuntimeException e) {
                last = e;
            }
        }
        throw new IllegalStateException(
                "Could not locate the Vulkan loader on this system (tried: "
                        + String.join(", ", candidates)
                        + "). Ensure the Vulkan runtime/ICD loader is installed.", last);
    }

    private static MethodHandle handle(String symbol, FunctionDescriptor descriptor) {
        MemorySegment addr = VULKAN_LOOKUP.find(symbol)
                .orElseThrow(() -> new UnsatisfiedLinkError(
                        "Vulkan symbol not found: " + symbol
                                + " (is the loader ICD-enabled and up to date?)"));
        return LINKER.downcallHandle(addr, descriptor);
    }

    // ---------------------------------------------------------------
    // VkResult vkCreateInstance(
    //     const VkInstanceCreateInfo* pCreateInfo,
    //     const VkAllocationCallbacks* pAllocator,
    //     VkInstance* pInstance);
    // ---------------------------------------------------------------
    public static final MethodHandle vkCreateInstance = handle(
            "vkCreateInstance",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    // void vkDestroyInstance(VkInstance instance, const VkAllocationCallbacks* pAllocator);
    public static final MethodHandle vkDestroyInstance = handle(
            "vkDestroyInstance",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    // VkResult vkEnumeratePhysicalDevices(
    //     VkInstance instance, uint32_t* pPhysicalDeviceCount, VkPhysicalDevice* pPhysicalDevices);
    public static final MethodHandle vkEnumeratePhysicalDevices = handle(
            "vkEnumeratePhysicalDevices",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

    // void vkGetPhysicalDeviceProperties(VkPhysicalDevice device, VkPhysicalDeviceProperties* pProperties);
    public static final MethodHandle vkGetPhysicalDeviceProperties = handle(
            "vkGetPhysicalDeviceProperties",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    // void vkGetPhysicalDeviceQueueFamilyProperties(
    //     VkPhysicalDevice device, uint32_t* pQueueFamilyPropertyCount, VkQueueFamilyProperties* pQueueFamilyProperties);
    public static final MethodHandle vkGetPhysicalDeviceQueueFamilyProperties = handle(
            "vkGetPhysicalDeviceQueueFamilyProperties",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    // VkResult vkEnumerateInstanceVersion(uint32_t* pApiVersion);  (core since 1.1, optional pre-1.1)
    public static final MethodHandle vkEnumerateInstanceVersion = handle(
            "vkEnumerateInstanceVersion",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    // ---------------------------------------------------------------
    // Phase 5: logical device, memory, buffers, descriptors, pipelines, commands
    // ---------------------------------------------------------------

    public static final MethodHandle vkCreateDevice = handle(
            "vkCreateDevice", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyDevice = handle(
            "vkDestroyDevice", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    public static final MethodHandle vkGetDeviceQueue = handle(
            "vkGetDeviceQueue", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
    public static final MethodHandle vkGetPhysicalDeviceMemoryProperties = handle(
            "vkGetPhysicalDeviceMemoryProperties", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    public static final MethodHandle vkCreateBuffer = handle(
            "vkCreateBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyBuffer = handle(
            "vkDestroyBuffer", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkGetBufferMemoryRequirements = handle(
            "vkGetBufferMemoryRequirements", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkAllocateMemory = handle(
            "vkAllocateMemory", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkFreeMemory = handle(
            "vkFreeMemory", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkBindBufferMemory = handle(
            "vkBindBufferMemory", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));
    public static final MethodHandle vkMapMemory = handle(
            "vkMapMemory", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
    public static final MethodHandle vkUnmapMemory = handle(
            "vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

    public static final MethodHandle vkCreateShaderModule = handle(
            "vkCreateShaderModule", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyShaderModule = handle(
            "vkDestroyShaderModule", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle vkCreateDescriptorSetLayout = handle(
            "vkCreateDescriptorSetLayout", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyDescriptorSetLayout = handle(
            "vkDestroyDescriptorSetLayout", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkCreatePipelineLayout = handle(
            "vkCreatePipelineLayout", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyPipelineLayout = handle(
            "vkDestroyPipelineLayout", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkCreateComputePipelines = handle(
            "vkCreateComputePipelines", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyPipeline = handle(
            "vkDestroyPipeline", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));

    public static final MethodHandle vkCreateDescriptorPool = handle(
            "vkCreateDescriptorPool", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyDescriptorPool = handle(
            "vkDestroyDescriptorPool", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkAllocateDescriptorSets = handle(
            "vkAllocateDescriptorSets", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkUpdateDescriptorSets = handle(
            "vkUpdateDescriptorSets", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

    public static final MethodHandle vkCreateCommandPool = handle(
            "vkCreateCommandPool", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkDestroyCommandPool = handle(
            "vkDestroyCommandPool", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkAllocateCommandBuffers = handle(
            "vkAllocateCommandBuffers", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    public static final MethodHandle vkBeginCommandBuffer = handle(
            "vkBeginCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle vkEndCommandBuffer = handle(
            "vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    public static final MethodHandle vkCmdBindPipeline = handle(
            "vkCmdBindPipeline", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS));
    public static final MethodHandle vkCmdBindDescriptorSets = handle(
            "vkCmdBindDescriptorSets",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    public static final MethodHandle vkCmdDispatch = handle(
            "vkCmdDispatch", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));

    public static final MethodHandle vkQueueSubmit = handle(
            "vkQueueSubmit", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    public static final MethodHandle vkQueueWaitIdle = handle(
            "vkQueueWaitIdle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    public static final MethodHandle vkDeviceWaitIdle = handle(
            "vkDeviceWaitIdle", FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** Throws with a decoded, human-readable message if result != VK_SUCCESS. */
    public static void check(int vkResult, String op) {
        if (vkResult != VulkanConstants.VK_SUCCESS) {
            throw new VulkanException(op, vkResult);
        }
    }

    public static final class VulkanException extends RuntimeException {
        public final int vkResult;

        public VulkanException(String op, int vkResult) {
            super(op + " failed: " + decode(vkResult) + " (" + vkResult + ")");
            this.vkResult = vkResult;
        }

        private static String decode(int r) {
            return switch (r) {
                case VulkanConstants.VK_ERROR_OUT_OF_HOST_MEMORY -> "VK_ERROR_OUT_OF_HOST_MEMORY";
                case VulkanConstants.VK_ERROR_OUT_OF_DEVICE_MEMORY -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
                case VulkanConstants.VK_ERROR_INITIALIZATION_FAILED -> "VK_ERROR_INITIALIZATION_FAILED";
                case VulkanConstants.VK_ERROR_LAYER_NOT_PRESENT -> "VK_ERROR_LAYER_NOT_PRESENT";
                case VulkanConstants.VK_ERROR_EXTENSION_NOT_PRESENT -> "VK_ERROR_EXTENSION_NOT_PRESENT";
                case VulkanConstants.VK_ERROR_INCOMPATIBLE_DRIVER -> "VK_ERROR_INCOMPATIBLE_DRIVER (no ICD/GPU driver found)";
                default -> "VkResult(" + r + ")";
            };
        }
    }
}
