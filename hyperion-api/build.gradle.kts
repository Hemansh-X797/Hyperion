plugins {
    id("java")
}

// Pure annotation module, no dependencies. hyperion-core depends on this
// module for @GpuKernel/@ParallelFor/@SharedMemory/@Vectorized.
