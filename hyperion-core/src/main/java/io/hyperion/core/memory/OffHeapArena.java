package io.hyperion.core.memory;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A tracked, alignment-aware wrapper around {@link Arena} for all of
 * Hyperion's off-heap tensor storage. Every allocation defaults to
 * {@link #DEFAULT_ALIGNMENT} (64 bytes) so buffers are simultaneously safe
 * for:
 *   - AVX-512 SIMD loads (Phase 7's Vector API fallback needs 64B alignment
 *     for the fastest aligned load/store instruction forms),
 *   - Vulkan storage-buffer offset alignment on most drivers (
 *     minStorageBufferOffsetAlignment is commonly 16-256B; 64B satisfies
 *     the overwhelming majority of real devices and is a safe default —
 *     Phase 5's descriptor binding code will still query and respect the
 *     device's actual minStorageBufferOffsetAlignment before binding).
 *
 * No JVM heap object backs tensor data — allocation, access, and freeing
 * all go through {@link MemorySegment}, so tensor memory is never visible
 * to GC and never triggers a collection.
 */
public final class OffHeapArena implements AutoCloseable {

    /** 64 bytes: AVX-512 register width and a safe common denominator for GPU buffer alignment. */
    public static final long DEFAULT_ALIGNMENT = 64L;

    private final Arena arena;
    private final boolean threadSafe;
    private final AtomicLong totalBytesAllocated = new AtomicLong();
    private final AtomicLong liveAllocationCount = new AtomicLong();
    private volatile boolean closed = false;

    private OffHeapArena(Arena arena, boolean threadSafe) {
        this.arena = arena;
        this.threadSafe = threadSafe;
    }

    /**
     * A confined arena: fastest allocation path, but every access (including
     * close()) must happen on the thread that created it. Use for
     * single-threaded kernel-building code.
     */
    public static OffHeapArena confined() {
        return new OffHeapArena(Arena.ofConfined(), false);
    }

    /**
     * A shared arena: slightly higher allocation overhead, but safe to
     * allocate from and access across multiple threads/virtual threads.
     * Use this for tensors that Phase 8's work-stealing scheduler will
     * touch from more than one virtual thread.
     */
    public static OffHeapArena shared() {
        return new OffHeapArena(Arena.ofShared(), true);
    }

    public boolean isThreadSafe() {
        return threadSafe;
    }

    /** Allocates {@code byteSize} bytes at the default 64-byte alignment. */
    public MemorySegment allocate(long byteSize) {
        return allocate(byteSize, DEFAULT_ALIGNMENT);
    }

    /** Allocates {@code byteSize} bytes at an explicit byte alignment (must be a power of two). */
    public MemorySegment allocate(long byteSize, long byteAlignment) {
        requireOpen();
        if (byteSize < 0) {
            throw new IllegalArgumentException("byteSize must be >= 0, got " + byteSize);
        }
        if (byteAlignment <= 0 || (byteAlignment & (byteAlignment - 1)) != 0) {
            throw new IllegalArgumentException("byteAlignment must be a power of two, got " + byteAlignment);
        }
        MemorySegment segment = arena.allocate(byteSize, byteAlignment);
        totalBytesAllocated.addAndGet(byteSize);
        liveAllocationCount.incrementAndGet();
        return segment;
    }

    /** Allocates and zero-fills — MemorySegment.allocate already zero-fills per the FFM spec,
     *  this method exists to make that guarantee explicit and greppable at call sites. */
    public MemorySegment allocateZeroed(long byteSize) {
        // Arena.allocate already returns zero-initialized memory (JEP 454 guarantee);
        // no redundant fill call needed. Kept as a distinctly-named entry point so
        // tensor code can assert its zero-init assumption is intentional, not accidental.
        return allocate(byteSize);
    }

    public long totalBytesAllocated() {
        return totalBytesAllocated.get();
    }

    public long liveAllocationCount() {
        return liveAllocationCount.get();
    }

    public Arena rawArena() {
        return arena;
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("OffHeapArena is closed");
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        arena.close();
    }

    @Override
    public String toString() {
        return "OffHeapArena{threadSafe=%s, liveAllocations=%d, totalBytes=%d, closed=%s}"
                .formatted(threadSafe, liveAllocationCount.get(), totalBytesAllocated.get(), closed);
    }
}
