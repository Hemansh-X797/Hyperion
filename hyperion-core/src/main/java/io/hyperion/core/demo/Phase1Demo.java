package io.hyperion.core.demo;

import io.hyperion.core.memory.DataType;
import io.hyperion.core.memory.OffHeapArena;
import io.hyperion.core.memory.OffHeapTensor;
import io.hyperion.core.memory.Shape;

public final class Phase1Demo {
    public static void main(String[] args) {
        System.out.println("=== Hyperion Phase 1: Zero-GC Off-Heap Tensor Memory Engine ===");

        try (OffHeapArena arena = OffHeapArena.confined()) {

            // --- 1. Allocation + real 64-byte alignment check ---
            OffHeapTensor t = OffHeapTensor.create(arena, Shape.of(4, 4), DataType.FLOAT32);
            System.out.println("Allocated: " + t);
            long addr = t.baseAddress();
            System.out.printf("Base address: 0x%x, addr %% 64 = %d (must be 0)%n", addr, addr % 64);
            if (addr % OffHeapArena.DEFAULT_ALIGNMENT != 0) {
                throw new AssertionError("Alignment check failed!");
            }

            // --- 2. Fill with a deterministic pattern: value = row*4 + col ---
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    t.setFloat(r * 4 + c, r, c);
                }
            }
            printMatrix("Original 4x4", t);

            // --- 3. Zero-copy slice: rows [1,3) ---
            OffHeapTensor sliced = t.slice(0, 1, 3);
            printMatrix("Sliced rows [1,3)", sliced);
            expect(sliced.getFloat(0, 0) == 4f, "slice[0,0] should read original row 1 = 4");
            expect(sliced.getFloat(1, 3) == 11f, "slice[1,3] should read original row 2,col 3 = 11");

            // --- 4. Zero-copy transpose ---
            OffHeapTensor transposed = t.transpose(1, 0);
            printMatrix("Transposed", transposed);
            expect(transposed.getFloat(2, 1) == t.getFloat(1, 2), "transpose[2,1] must equal original[1,2]");
            expect(!transposed.isContiguous(), "a transposed view must not be contiguous");

            // --- 5. reshape() must reject the non-contiguous transposed view ---
            boolean rejected = false;
            try {
                transposed.reshape(Shape.of(16));
            } catch (IllegalStateException expected) {
                rejected = true;
                System.out.println("reshape() correctly rejected non-contiguous view: " + expected.getMessage());
            }
            expect(rejected, "reshape must reject a non-contiguous (transposed) view");

            // --- 6. reshape() succeeds on the original contiguous tensor ---
            OffHeapTensor flat = t.reshape(Shape.of(16));
            System.out.println("Reshaped to " + flat.shape() + ", flat[6] = " + flat.getFloat(6)
                    + " (expected 6.0, since original[1,2] = 1*4+2 = 6)");
            expect(flat.getFloat(6) == 6f, "reshape must preserve row-major element order");

            // --- 7. copy(): materialize the transposed (non-contiguous) view into a fresh contiguous tensor ---
            OffHeapTensor materialized = transposed.copy(arena);
            printMatrix("Materialized copy of the transpose", materialized);
            expect(materialized.isContiguous(), "a fresh copy() must be contiguous");
            expect(materialized.getFloat(2, 1) == t.getFloat(1, 2), "materialized copy must preserve transposed values");

            System.out.println("\nArena stats: " + arena);
            System.out.println("All Phase 1 correctness checks passed.");
        }

        System.out.println("=== Phase 1 complete: arena closed, all off-heap memory released, zero GC involvement ===");
    }

    private static void printMatrix(String label, OffHeapTensor m) {
        StringBuilder sb = new StringBuilder(label).append(" ").append(m.shape()).append(":\n");
        for (int r = 0; r < m.shape().dim(0); r++) {
            sb.append("  [");
            for (int c = 0; c < m.shape().dim(1); c++) {
                if (c > 0) sb.append(", ");
                sb.append(m.getFloat(r, c));
            }
            sb.append("]\n");
        }
        System.out.print(sb);
    }

    private static void expect(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError("CHECK FAILED: " + message);
        }
        System.out.println("  [ok] " + message);
    }
}
