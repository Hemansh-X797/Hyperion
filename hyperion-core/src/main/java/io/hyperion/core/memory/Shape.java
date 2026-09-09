package io.hyperion.core.memory;

import java.util.Arrays;

/**
 * An immutable N-dimensional shape. Strides are always expressed in
 * <em>elements</em>, not bytes — byte offsets are computed by the tensor
 * using its {@link DataType#byteWidth()} at access time, which keeps shape
 * math independent of element size (needed once quantized types enter the
 * picture in Phase 10, where "elements" and "bytes" diverge).
 */
public final class Shape {

    private final int[] dims;

    private Shape(int[] dims) {
        this.dims = dims;
    }

    public static Shape of(int... dims) {
        if (dims.length == 0) {
            throw new IllegalArgumentException("Shape must have rank >= 1 (use Shape.of(1) for a scalar)");
        }
        for (int d : dims) {
            if (d < 0) {
                throw new IllegalArgumentException("Shape dimensions must be >= 0, got: " + Arrays.toString(dims));
            }
        }
        return new Shape(dims.clone());
    }

    public int rank() {
        return dims.length;
    }

    public int dim(int axis) {
        return dims[normalizeAxis(axis)];
    }

    public int[] dims() {
        return dims.clone();
    }

    /** Total element count = product of all dimensions. */
    public long elementCount() {
        long n = 1;
        for (int d : dims) n *= d;
        return n;
    }

    /**
     * Standard C-order (row-major) contiguous strides, in elements: the
     * last axis is fastest-varying, stride 1; each preceding axis's stride
     * is the product of all faster axes' dimensions.
     */
    public long[] contiguousStrides() {
        long[] strides = new long[dims.length];
        long acc = 1;
        for (int i = dims.length - 1; i >= 0; i--) {
            strides[i] = acc;
            acc *= dims[i];
        }
        return strides;
    }

    /** Negative axis indices count from the end, like NumPy (-1 == last axis). */
    public int normalizeAxis(int axis) {
        int a = axis < 0 ? axis + dims.length : axis;
        if (a < 0 || a >= dims.length) {
            throw new IndexOutOfBoundsException(
                    "axis " + axis + " out of bounds for rank-" + dims.length + " shape " + this);
        }
        return a;
    }

    public Shape withDim(int axis, int newSize) {
        int a = normalizeAxis(axis);
        int[] copy = dims.clone();
        copy[a] = newSize;
        return new Shape(copy);
    }

    public Shape dropAxis(int axis) {
        int a = normalizeAxis(axis);
        int[] copy = new int[dims.length - 1];
        System.arraycopy(dims, 0, copy, 0, a);
        System.arraycopy(dims, a + 1, copy, a, dims.length - a - 1);
        if (copy.length == 0) {
            throw new IllegalStateException("Cannot drop the only axis of a rank-1 shape");
        }
        return new Shape(copy);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Shape s && Arrays.equals(dims, s.dims);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(dims);
    }

    @Override
    public String toString() {
        return Arrays.toString(dims);
    }
}
