package io.hyperion.api.annotation;

import java.lang.annotation.*;

/**
 * Marks a loop construct (captured structurally in Phase 3's CFG/SSA
 * lowering, not by this annotation directly — annotations cannot target
 * statements in Java) as eligible for parallelization across GPU
 * work-items / CPU SIMD lanes. In the current Phase 2 baseline this
 * annotation is accepted on methods as a hint that the *entire* kernel
 * body represents one work-item's execution (the common "grid-stride"
 * kernel shape); finer-grained per-loop hints arrive with Phase 3.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ParallelFor {
    /** Requested workgroup size hint per dimension, e.g. {256} or {16, 16}. 0 = let the scheduler decide. */
    int[] workgroupSize() default {};
}
