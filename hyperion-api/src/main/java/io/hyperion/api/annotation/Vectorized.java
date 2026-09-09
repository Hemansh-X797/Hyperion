package io.hyperion.api.annotation;

import java.lang.annotation.*;

/**
 * Hints that a kernel is eligible for Phase 7's Vector API (AVX-512/ARM
 * Neon) CPU fallback lowering when no GPU is available, in addition to
 * the normal Vulkan/SPIR-V path. Purely advisory in the Phase 2 baseline —
 * the AstExtractor records it on {@link io.hyperion.core.reflection.KernelMetadata}
 * but does not yet act on it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Vectorized {
}
