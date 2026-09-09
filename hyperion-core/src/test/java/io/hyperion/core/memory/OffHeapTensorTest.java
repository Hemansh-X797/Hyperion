package io.hyperion.core.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OffHeapTensorTest {

    @Test
    void allocationIs64ByteAligned() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            for (int i = 0; i < 20; i++) {
                // odd sizes on purpose, to make sure the allocator is doing
                // real alignment work and not just getting lucky with round sizes
                OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(3, 5, 7), DataType.FLOAT32);
                assertEquals(0, t.baseAddress() % OffHeapArena.DEFAULT_ALIGNMENT,
                        "allocation " + i + " is not 64-byte aligned");
            }
        }
    }

    @Test
    void contiguousStridesAreRowMajor() {
        Shape s = Shape.of(2, 3, 4);
        long[] strides = s.contiguousStrides();
        assertArrayEquals(new long[] {12, 4, 1}, strides);
    }

    @Test
    void sliceIsZeroCopyAndReadsOriginalData() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(4, 4), DataType.FLOAT32);
            for (int r = 0; r < 4; r++)
                for (int c = 0; c < 4; c++)
                    t.setFloat(r * 4 + c, r, c);

            OffHeapTensor sliced = t.slice(0, 1, 3);
            assertEquals(Shape.of(2, 4), sliced.shape());
            assertEquals(4f, sliced.getFloat(0, 0));
            assertEquals(11f, sliced.getFloat(1, 3));

            // prove zero-copy: mutating through the slice must be visible in the original
            sliced.setFloat(999f, 0, 0);
            assertEquals(999f, t.getFloat(1, 0));
        }
    }

    @Test
    void transposeProducesNonContiguousView() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(2, 3), DataType.FLOAT32);
            OffHeapTensor tr = t.transpose(1, 0);
            assertEquals(Shape.of(3, 2), tr.shape());
            assertFalse(tr.isContiguous());
            assertTrue(t.isContiguous());
        }
    }

    @Test
    void reshapeRejectsNonContiguousView() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(2, 3), DataType.FLOAT32);
            OffHeapTensor tr = t.transpose(1, 0);
            assertThrows(IllegalStateException.class, () -> tr.reshape(Shape.of(6)));
        }
    }

    @Test
    void reshapePreservesRowMajorOrder() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(2, 3), DataType.FLOAT32);
            for (int i = 0; i < 6; i++) t.setFloat(i, i / 3, i % 3);
            OffHeapTensor flat = t.reshape(Shape.of(6));
            for (int i = 0; i < 6; i++) assertEquals((float) i, flat.getFloat(i));
        }
    }

    @Test
    void copyMaterializesNonContiguousViewCorrectly() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(2, 3), DataType.FLOAT32);
            for (int i = 0; i < 6; i++) t.setFloat(i, i / 3, i % 3);
            OffHeapTensor tr = t.transpose(1, 0);
            OffHeapTensor materialized = tr.copy(arena);

            assertTrue(materialized.isContiguous());
            for (int r = 0; r < 3; r++)
                for (int c = 0; c < 2; c++)
                    assertEquals(tr.getFloat(r, c), materialized.getFloat(r, c));
        }
    }

    @Test
    void outOfBoundsIndexThrows() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(2, 2), DataType.FLOAT32);
            assertThrows(IndexOutOfBoundsException.class, () -> t.getFloat(2, 0));
            assertThrows(IndexOutOfBoundsException.class, () -> t.getFloat(0, -1));
        }
    }

    @Test
    void wrongDtypeAccessThrows() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(4), DataType.INT32);
            assertThrows(IllegalStateException.class, () -> t.getFloat(0));
        }
    }

    @Test
    void arenaRejectsAllocationAfterClose() {
        OffHeapArena arena = OffHeapArena.confined();
        arena.close();
        assertThrows(IllegalStateException.class, () -> arena.allocate(64));
    }

    @Test
    void arenaTracksAllocationStats() {
        try (OffHeapArena arena = OffHeapArena.confined()) {
            assertEquals(0, arena.liveAllocationCount());
            OffHeapTensor.create(arena, Shape.of(10), DataType.FLOAT32);
            OffHeapTensor.create(arena, Shape.of(20), DataType.FLOAT32);
            assertEquals(2, arena.liveAllocationCount());
            assertEquals(40 + 80, arena.totalBytesAllocated());
        }
    }
}
