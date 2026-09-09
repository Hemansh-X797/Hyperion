package io.hyperion.api.annotation;

import java.lang.annotation.*;

/**
 * Marks a kernel parameter as backed by GPU workgroup-local ("shared")
 * memory rather than global device memory — maps to SPIR-V's
 * StorageClass.Workgroup in Phase 4/6 (Shared Memory Promotion pass).
 * Ignored by the Phase 2 baseline extractor (which only handles primitive
 * scalar params); wired up once Phase 3 adds array/buffer parameter
 * support.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface SharedMemory {
    /** Number of elements to allocate in workgroup-local memory. */
    int size() default 0;
}
