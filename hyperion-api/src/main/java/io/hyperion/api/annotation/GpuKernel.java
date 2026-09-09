package io.hyperion.api.annotation;

import java.lang.annotation.*;

/**
 * Marks a method as a Hyperion GPU kernel: its body will be captured via
 * bytecode-based AST extraction (Phase 2), lowered to Hyperion SSA IR
 * (Phase 3), and compiled to SPIR-V (Phase 4) for dispatch over Vulkan.
 *
 * Current extraction support (Phase 2 baseline, see AstExtractor): static
 * methods with primitive (int/long/float/double) parameters and a single
 * primitive return, whose body is straight-line arithmetic — no branches,
 * loops, or calls yet. Those land in Phase 3's CFG/SSA lowering.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface GpuKernel {
    /** Optional kernel name for diagnostics/SPIR-V debug info; defaults to the method name. */
    String name() default "";
}
