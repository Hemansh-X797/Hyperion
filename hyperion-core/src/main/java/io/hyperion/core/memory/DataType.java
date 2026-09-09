package io.hyperion.core.memory;

import java.lang.foreign.ValueLayout;

import static java.lang.foreign.ValueLayout.*;

/**
 * Element data types Hyperion tensors can hold off-heap. Each carries its
 * exact byte width and the matching {@link ValueLayout} used for FFM
 * get/set access, so there is exactly one source of truth for "how big is
 * an element" across the whole memory engine (and later, the SPIR-V/kernel
 * layers — quantized types included, since GGUF weight loading in Phase 10
 * needs them).
 */
public enum DataType {
    FLOAT32(4, JAVA_FLOAT),
    FLOAT64(8, JAVA_DOUBLE),
    INT32(4, JAVA_INT),
    INT64(8, JAVA_LONG),
    INT8(1, JAVA_BYTE),
    UINT8(1, JAVA_BYTE),
    BOOL8(1, JAVA_BYTE),

    /** IEEE-754 binary16. No native Java primitive; stored as raw 2-byte
     *  short bit patterns, converted at the edges via {@link #FLOAT32}. */
    FLOAT16(2, JAVA_SHORT),

    /** bfloat16: top 16 bits of a float32 (sign + 8 exp + 7 mantissa). */
    BFLOAT16(2, JAVA_SHORT),

    /** llama.cpp-style block-quantized 4-bit weights, 32 values per block:
     *  2 bytes fp16 scale + 16 bytes of packed 4-bit values = 18 bytes/block.
     *  Element "byteWidth" here is nominal (used for block math in Phase 10's
     *  GGUF loader, not for direct per-element indexed access). */
    Q4_0(18, JAVA_BYTE, 32),

    /** 8-bit block-quantized: 2 bytes fp16 scale + 32 bytes int8 values = 34 bytes/block. */
    Q8_0(34, JAVA_BYTE, 32);

    private final int byteWidth;
    private final ValueLayout layout;
    private final int blockSize; // elements per block; 1 for non-quantized types

    DataType(int byteWidth, ValueLayout layout) {
        this(byteWidth, layout, 1);
    }

    DataType(int byteWidth, ValueLayout layout, int blockSize) {
        this.byteWidth = byteWidth;
        this.layout = layout;
        this.blockSize = blockSize;
    }

    /** For non-quantized types: bytes per element. For quantized types: bytes per block. */
    public int byteWidth() { return byteWidth; }

    public ValueLayout layout() { return layout; }

    public boolean isQuantized() { return blockSize > 1; }

    public int blockSize() { return blockSize; }

    /**
     * Bytes required to store {@code elementCount} elements of this type.
     * For quantized types this rounds up to whole blocks, matching GGUF's
     * on-disk layout (partial trailing blocks are not valid GGUF).
     */
    public long bytesFor(long elementCount) {
        if (blockSize == 1) {
            return elementCount * byteWidth;
        }
        long blocks = (elementCount + blockSize - 1) / blockSize;
        return blocks * byteWidth;
    }
}
