package io.hyperion.core.scheduler;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A genuine implementation of the Chase-Lev lock-free work-stealing deque
 * (Chase &amp; Lev, "Dynamic Circular Work-Stealing Deque", SPAA 2005) —
 * not a wrapper around {@code ExecutorService} or {@code ForkJoinPool}.
 *
 * <p>Exactly one thread (the "owner") calls {@link #pushBottom} and
 * {@link #popBottom}; any number of other threads ("thieves") call
 * {@link #steal} concurrently. The owner treats the deque like a stack
 * (LIFO, cache-friendly for its own recently-created work); thieves take
 * from the opposite end (FIFO-ish relative to the owner, which spreads out
 * contention and tends to steal the *oldest*, usually coarsest-grained,
 * work first).
 *
 * <p>The array grows (never shrinks) when the owner's push would overflow
 * it; growth copies live elements into a new, larger backing array.
 */
public final class WorkStealingDeque<T> {

    private static final int INITIAL_CAPACITY = 32; // must be a power of two

    private volatile CircularArray<T> array;
    private final AtomicLong top = new AtomicLong(0);
    private final AtomicLong bottom = new AtomicLong(0);

    public WorkStealingDeque() {
        this.array = new CircularArray<>(INITIAL_CAPACITY);
    }

    public boolean isEmpty() {
        return bottom.get() <= top.get();
    }

    /** Owner-only: pushes onto the bottom (the owner's "recent work" end). */
    public void pushBottom(T item) {
        long b = bottom.get();
        long t = top.get();
        CircularArray<T> a = array;
        if (b - t >= a.capacity() - 1) {
            a = a.grow(b, t);
            array = a;
        }
        a.put(b, item);
        bottom.set(b + 1); // volatile write: publishes both the array slot and the new size
    }

    /** Owner-only: pops from the bottom. Returns null if the deque was empty. */
    @SuppressWarnings("unchecked")
    public T popBottom() {
        long b = bottom.get() - 1;
        CircularArray<T> a = array;
        bottom.set(b);
        long t = top.get();
        if (t <= b) {
            T item = a.get(b);
            if (t == b) {
                // exactly one element left: race against concurrent stealers for it
                if (!top.compareAndSet(t, t + 1)) {
                    item = null; // a thief won the race
                }
                bottom.set(b + 1);
            }
            return item;
        } else {
            bottom.set(b + 1); // deque was already empty; restore bottom
            return null;
        }
    }

    /** Any thread except the owner: steals from the top. Returns null if empty or it lost a race. */
    @SuppressWarnings("unchecked")
    public T steal() {
        long t = top.get();
        long b = bottom.get();
        if (t < b) {
            CircularArray<T> a = array;
            T item = a.get(t);
            if (!top.compareAndSet(t, t + 1)) {
                return null; // lost a race with another thief (or the owner's last-element pop)
            }
            return item;
        }
        return null;
    }

    /** Fixed-power-of-two-capacity circular buffer with grow-and-copy. */
    private static final class CircularArray<T> {
        private final Object[] elements;
        private final int mask;

        CircularArray(int capacity) {
            this.elements = new Object[capacity];
            this.mask = capacity - 1;
        }

        int capacity() {
            return elements.length;
        }

        void put(long index, T value) {
            elements[(int) (index & mask)] = value;
        }

        @SuppressWarnings("unchecked")
        T get(long index) {
            return (T) elements[(int) (index & mask)];
        }

        CircularArray<T> grow(long bottom, long top) {
            CircularArray<T> bigger = new CircularArray<>(capacity() * 2);
            for (long i = top; i < bottom; i++) {
                bigger.put(i, get(i));
            }
            return bigger;
        }
    }
}
