package io.hyperion.core.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.Arrays;

/**
 * A multi-dimensional tensor view over off-heap memory. A tensor is always
 * a (segment, elementOffset, shape, strides, dtype) tuple — slicing,
 * transposing, and reshaping-when-contiguous never copy data, they just
 * produce a new view tuple over the same underlying {@link MemorySegment}.
 *
 * Strides are in <em>elements</em>; byte offsets are computed on access as
 * {@code (elementOffset + sum(index[i] * stride[i])) * dtype.byteWidth()}.
 * This class only supports non-quantized element types (byteWidth == 1
 * element); quantized block types (Q4_0/Q8_0) are handled by dedicated
 * block-level accessors in Phase 10's GGUF loader, not here.
 */
public final class OffHeapTensor {

    private final MemorySegment segment; // the full backing allocation (may be larger than this view)
    private final long elementOffset;    // element index of this view's origin within `segment`
    private final Shape shape;
    private final long[] strides;        // element strides, one per axis, same length as shape.rank()
    private final DataType dtype;

    private OffHeapTensor(MemorySegment segment, long elementOffset, Shape shape, long[] strides, DataType dtype) {
        if (dtype.isQuantized()) {
            throw new IllegalArgumentException(
                    "OffHeapTensor does not support quantized dtype " + dtype
                            + " for element-indexed access; use block-level access instead");
        }
        if (shape.rank() != strides.length) {
            throw new IllegalArgumentException("shape rank " + shape.rank()
                    + " does not match strides length " + strides.length);
        }
        this.segment = segment;
        this.elementOffset = elementOffset;
        this.shape = shape;
        this.strides = strides;
        this.dtype = dtype;
    }

    /** Allocates a brand-new, contiguous (row-major), zero-initialized tensor. */
    public static OffHeapTensor create(OffHeapArena arena, Shape shape, DataType dtype) {
        long elementCount = shape.elementCount();
        long byteSize = dtype.bytesFor(elementCount);
        MemorySegment seg = arena.allocate(byteSize);
        return new OffHeapTensor(seg, 0L, shape, shape.contiguousStrides(), dtype);
    }

    public Shape shape() { return shape; }
    public DataType dtype() { return dtype; }
    public int rank() { return shape.rank(); }
    public long elementCount() { return shape.elementCount(); }
    public long[] strides() { return strides.clone(); }

    /** True if this view's strides match a fresh row-major layout for its shape
     *  (i.e. it can be reshaped without a copy). */
    public boolean isContiguous() {
        return Arrays.equals(strides, shape.contiguousStrides());
    }

    // ---------------------------------------------------------------
    // Index math
    // ---------------------------------------------------------------

    private long flatElementIndex(int... indices) {
        if (indices.length != shape.rank()) {
            throw new IllegalArgumentException(
                    "Expected " + shape.rank() + " indices for shape " + shape + ", got " + indices.length);
        }
        long flat = elementOffset;
        for (int i = 0; i < indices.length; i++) {
            int idx = indices[i];
            int dim = shape.dim(i);
            if (idx < 0 || idx >= dim) {
                throw new IndexOutOfBoundsException(
                        "index " + idx + " out of bounds for axis " + i + " with size " + dim);
            }
            flat += (long) idx * strides[i];
        }
        return flat;
    }

    private long byteOffset(int... indices) {
        return flatElementIndex(indices) * dtype.byteWidth();
    }

    // ---------------------------------------------------------------
    // Typed element access — caller must match the tensor's dtype.
    // ---------------------------------------------------------------

    public float getFloat(int... indices) {
        requireDtype(DataType.FLOAT32);
        return segment.get(ValueLayout.JAVA_FLOAT, byteOffset(indices));
    }

    public void setFloat(float value, int... indices) {
        requireDtype(DataType.FLOAT32);
        segment.set(ValueLayout.JAVA_FLOAT, byteOffset(indices), value);
    }

    public double getDouble(int... indices) {
        requireDtype(DataType.FLOAT64);
        return segment.get(ValueLayout.JAVA_DOUBLE, byteOffset(indices));
    }

    public void setDouble(double value, int... indices) {
        requireDtype(DataType.FLOAT64);
        segment.set(ValueLayout.JAVA_DOUBLE, byteOffset(indices), value);
    }

    public int getInt(int... indices) {
        requireDtype(DataType.INT32);
        return segment.get(ValueLayout.JAVA_INT, byteOffset(indices));
    }

    public void setInt(int value, int... indices) {
        requireDtype(DataType.INT32);
        segment.set(ValueLayout.JAVA_INT, byteOffset(indices), value);
    }

    public long getLong(int... indices) {
        requireDtype(DataType.INT64);
        return segment.get(ValueLayout.JAVA_LONG, byteOffset(indices));
    }

    public void setLong(long value, int... indices) {
        requireDtype(DataType.INT64);
        segment.set(ValueLayout.JAVA_LONG, byteOffset(indices), value);
    }

    public byte getByte(int... indices) {
        return segment.get(ValueLayout.JAVA_BYTE, byteOffset(indices));
    }

    public void setByte(byte value, int... indices) {
        segment.set(ValueLayout.JAVA_BYTE, byteOffset(indices), value);
    }

    private void requireDtype(DataType expected) {
        if (dtype != expected) {
            throw new IllegalStateException("Tensor dtype is " + dtype + ", not " + expected);
        }
    }

    // ---------------------------------------------------------------
    // Views: slice / transpose / reshape — zero-copy
    // ---------------------------------------------------------------

    /**
     * A zero-copy view narrowing axis {@code axis} to the half-open element
     * range [start, end). All other axes and strides are unchanged.
     */
    public OffHeapTensor slice(int axis, int start, int end) {
        int a = shape.normalizeAxis(axis);
        int dim = shape.dim(a);
        if (start < 0 || end > dim || start > end) {
            throw new IndexOutOfBoundsException(
                    "slice[" + start + "," + end + ") out of bounds for axis " + a + " size " + dim);
        }
        long newOffset = elementOffset + (long) start * strides[a];
        Shape newShape = shape.withDim(a, end - start);
        return new OffHeapTensor(segment, newOffset, newShape, strides.clone(), dtype);
    }

    /**
     * A zero-copy view with axes reordered per {@code permutation} (a
     * permutation of {@code [0, rank)}), e.g. {@code transpose(1, 0)} swaps
     * the two axes of a matrix.
     */
    public OffHeapTensor transpose(int... permutation) {
        if (permutation.length != shape.rank()) {
            throw new IllegalArgumentException(
                    "permutation length " + permutation.length + " != rank " + shape.rank());
        }
        boolean[] seen = new boolean[permutation.length];
        int[] newDims = new int[permutation.length];
        long[] newStrides = new long[permutation.length];
        for (int i = 0; i < permutation.length; i++) {
            int p = permutation[i];
            if (p < 0 || p >= permutation.length || seen[p]) {
                throw new IllegalArgumentException("Invalid permutation: " + Arrays.toString(permutation));
            }
            seen[p] = true;
            newDims[i] = shape.dim(p);
            newStrides[i] = strides[p];
        }
        return new OffHeapTensor(segment, elementOffset, Shape.of(newDims), newStrides, dtype);
    }

    /**
     * Reinterprets this tensor's data under a new shape with the same total
     * element count. Only valid on a contiguous tensor (use {@link #copy}
     * first if this view is a non-contiguous slice/transpose).
     */
    public OffHeapTensor reshape(Shape newShape) {
        if (!isContiguous()) {
            throw new IllegalStateException(
                    "Cannot reshape a non-contiguous view (strides=" + Arrays.toString(strides)
                            + "); call copy() first to materialize a contiguous tensor");
        }
        if (newShape.elementCount() != shape.elementCount()) {
            throw new IllegalArgumentException("reshape element count mismatch: "
                    + shape + " (" + shape.elementCount() + ") -> " + newShape + " (" + newShape.elementCount() + ")");
        }
        return new OffHeapTensor(segment, elementOffset, newShape, newShape.contiguousStrides(), dtype);
    }

    /** Materializes a fresh, contiguous, independent copy of this view's data. */
    public OffHeapTensor copy(OffHeapArena arena) {
        OffHeapTensor dest = create(arena, shape, dtype);
        copyInto(dest);
        return dest;
    }

    /** Element-wise copy of this view's data into {@code dest} (shapes must match; strides may differ). */
    public void copyInto(OffHeapTensor dest) {
        if (!dest.shape.equals(this.shape)) {
            throw new IllegalArgumentException("Shape mismatch: source " + shape + " vs dest " + dest.shape);
        }
        if (dest.dtype != this.dtype) {
            throw new IllegalArgumentException("Dtype mismatch: source " + dtype + " vs dest " + dest.dtype);
        }
        int[] idx = new int[shape.rank()];
        copyRecursive(dest, idx, 0);
    }

    private void copyRecursive(OffHeapTensor dest, int[] idx, int axis) {
        if (axis == shape.rank()) {
            long srcByteOff = byteOffset(idx);
            long dstByteOff = dest.byteOffset(idx);
            // Raw byte-width copy — correct for every non-quantized dtype
            // since we copy exactly dtype.byteWidth() bytes regardless of
            // interpretation.
            MemorySegment.copy(segment, srcByteOff, dest.segment, dstByteOff, dtype.byteWidth());
            return;
        }
        int dim = shape.dim(axis);
        for (int i = 0; i < dim; i++) {
            idx[axis] = i;
            copyRecursive(dest, idx, axis + 1);
        }
    }

    /** Fills every element of a FLOAT32 tensor with {@code value} (contiguous fast path when possible). */
    public void fill(float value) {
        requireDtype(DataType.FLOAT32);
        if (isContiguous()) {
            // Fast path: one linear pass over the backing bytes for this view's element range.
            long n = shape.elementCount();
            for (long i = 0; i < n; i++) {
                segment.set(ValueLayout.JAVA_FLOAT, (elementOffset + i) * dtype.byteWidth(), value);
            }
        } else {
            int[] idx = new int[shape.rank()];
            fillRecursive(value, idx, 0);
        }
    }

    private void fillRecursive(float value, int[] idx, int axis) {
        if (axis == shape.rank()) {
            segment.set(ValueLayout.JAVA_FLOAT, byteOffset(idx), value);
            return;
        }
        int dim = shape.dim(axis);
        for (int i = 0; i < dim; i++) {
            idx[axis] = i;
            fillRecursive(value, idx, axis + 1);
        }
    }

    /** The starting native memory address of this view's first element (for FFM/Vulkan interop). */
    public long baseAddress() {
        return segment.address() + elementOffset * dtype.byteWidth();
    }

    @Override
    public String toString() {
        return "OffHeapTensor{shape=%s, dtype=%s, strides=%s, contiguous=%b, elementOffset=%d}"
                .formatted(shape, dtype, Arrays.toString(strides), isContiguous(), elementOffset);
    }
}
