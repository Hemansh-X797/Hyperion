package io.hyperion.runtime.vulkan.device;

import io.hyperion.runtime.vulkan.ffm.StructLayouts;
import io.hyperion.runtime.vulkan.ffm.VulkanConstants;
import io.hyperion.runtime.vulkan.ffm.VulkanFFM;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Bootstraps a VkInstance using nothing but the FFM API. This is the single
 * entry point through which Hyperion talks to the GPU driver — no CUDA, no
 * glslang, no native glue library of our own.
 */
public final class VulkanInstanceBootstrap implements AutoCloseable {

    private final Arena arena;
    private final MemorySegment instanceHandle; // VkInstance (opaque pointer)

    private VulkanInstanceBootstrap(Arena arena, MemorySegment instanceHandle) {
        this.arena = arena;
        this.instanceHandle = instanceHandle;
    }

    public MemorySegment handle() {
        return instanceHandle;
    }

    /**
     * Creates a VkInstance with the given application name and requested
     * instance extensions (may be empty). Uses a confined Arena scoped to
     * the lifetime of the returned bootstrap object.
     */
    public static VulkanInstanceBootstrap create(String appName, String[] requestedExtensions) {
        Arena arena = Arena.ofShared();
        try {
            MemorySegment appNameSeg = arena.allocateFrom(appName);
            MemorySegment engineNameSeg = arena.allocateFrom("Hyperion");

            MemorySegment appInfo = arena.allocate(StructLayouts.VK_APPLICATION_INFO);
            StructLayouts.VK_APP_INFO_sType.set(appInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_APPLICATION_INFO);
            StructLayouts.VK_APP_INFO_pNext.set(appInfo, 0L, MemorySegment.NULL);
            StructLayouts.VK_APP_INFO_pApplicationName.set(appInfo, 0L, appNameSeg);
            StructLayouts.VK_APP_INFO_applicationVersion.set(appInfo, 0L, VulkanConstants.makeApiVersion(0, 1, 0, 0));
            StructLayouts.VK_APP_INFO_pEngineName.set(appInfo, 0L, engineNameSeg);
            StructLayouts.VK_APP_INFO_engineVersion.set(appInfo, 0L, VulkanConstants.makeApiVersion(0, 1, 0, 0));
            StructLayouts.VK_APP_INFO_apiVersion.set(appInfo, 0L, VulkanConstants.VK_API_VERSION_1_3);

            // Build the char** array of extension name pointers, if any.
            MemorySegment ppExtensions = MemorySegment.NULL;
            int extCount = requestedExtensions == null ? 0 : requestedExtensions.length;
            if (extCount > 0) {
                ppExtensions = arena.allocate(ADDRESS, extCount);
                for (int i = 0; i < extCount; i++) {
                    MemorySegment nameSeg = arena.allocateFrom(requestedExtensions[i]);
                    ppExtensions.setAtIndex(ADDRESS, i, nameSeg);
                }
            }

            MemorySegment createInfo = arena.allocate(StructLayouts.VK_INSTANCE_CREATE_INFO);
            StructLayouts.VK_ICI_sType.set(createInfo, 0L, VulkanConstants.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            StructLayouts.VK_ICI_pNext.set(createInfo, 0L, MemorySegment.NULL);
            StructLayouts.VK_ICI_flags.set(createInfo, 0L, 0);
            StructLayouts.VK_ICI_pApplicationInfo.set(createInfo, 0L, appInfo);
            StructLayouts.VK_ICI_enabledLayerCount.set(createInfo, 0L, 0);
            StructLayouts.VK_ICI_ppEnabledLayerNames.set(createInfo, 0L, MemorySegment.NULL);
            StructLayouts.VK_ICI_enabledExtensionCount.set(createInfo, 0L, extCount);
            StructLayouts.VK_ICI_ppEnabledExtensionNames.set(createInfo, 0L, ppExtensions);

            MemorySegment pInstance = arena.allocate(ADDRESS);

            int result = (int) VulkanFFM.vkCreateInstance.invokeExact(
                    (MemorySegment) createInfo, (MemorySegment) MemorySegment.NULL, (MemorySegment) pInstance);
            VulkanFFM.check(result, "vkCreateInstance");

            MemorySegment instance = pInstance.get(ADDRESS, 0);
            return new VulkanInstanceBootstrap(arena, instance);
        } catch (Throwable t) {
            arena.close();
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Failed to create Vulkan instance", t);
        }
    }

    /** Queries the highest Vulkan API version the loader/driver supports (core 1.1+). */
    public static int queryInstanceVersion() {
        try (Arena a = Arena.ofConfined()) {
            MemorySegment pVersion = a.allocate(JAVA_INT);
            int result = (int) VulkanFFM.vkEnumerateInstanceVersion.invokeExact((MemorySegment) pVersion);
            VulkanFFM.check(result, "vkEnumerateInstanceVersion");
            return pVersion.get(JAVA_INT, 0);
        } catch (Throwable t) {
            if (t instanceof RuntimeException re) throw re;
            throw new RuntimeException("Failed to query instance version", t);
        }
    }

    @Override
    public void close() {
        try {
            MethodHandle destroy = VulkanFFM.vkDestroyInstance;
            destroy.invokeExact((MemorySegment) instanceHandle, (MemorySegment) MemorySegment.NULL);
        } catch (Throwable ignored) {
            // best-effort teardown
        } finally {
            arena.close();
        }
    }
}
