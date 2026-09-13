package io.hyperion.core.scheduler;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SchedulerTest {

    @Test
    void dequeDeliversEveryItemExactlyOnceUnderConcurrentStealing() throws Exception {
        int n = 200_000;
        int thieves = 8;
        WorkStealingDeque<Integer> deque = new WorkStealingDeque<>();
        AtomicIntegerArray seenCount = new AtomicIntegerArray(n);
        AtomicInteger delivered = new AtomicInteger(0);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(thieves);
        volatile boolean[] stop = {false};

        for (int t = 0; t < thieves; t++) {
            Thread.ofVirtual().start(() -> {
                try { start.await(); } catch (InterruptedException ignored) {}
                while (!stop[0]) {
                    Integer item = deque.steal();
                    if (item != null) {
                        assertEquals(0, seenCount.getAndIncrement(item), "duplicate delivery of " + item);
                        delivered.incrementAndGet();
                    } else {
                        Thread.onSpinWait();
                    }
                }
                done.countDown();
            });
        }

        start.countDown();
        for (int i = 0; i < n; i++) {
            deque.pushBottom(i);
            if (i % 5 == 0) {
                Integer popped = deque.popBottom();
                if (popped != null) {
                    assertEquals(0, seenCount.getAndIncrement(popped), "duplicate delivery of " + popped);
                    delivered.incrementAndGet();
                }
            }
        }
        Integer leftover;
        while ((leftover = deque.popBottom()) != null) {
            assertEquals(0, seenCount.getAndIncrement(leftover), "duplicate delivery of " + leftover);
            delivered.incrementAndGet();
        }
        Thread.sleep(300);
        stop[0] = true;
        done.await();

        assertEquals(n, delivered.get(), "not every item was delivered exactly once");
        for (int i = 0; i < n; i++) {
            assertEquals(1, seenCount.get(i), "item " + i + " was lost or duplicated");
        }
    }

    @Test
    void schedulerExecutesAllSubmittedTasks() throws Exception {
        try (HyperionScheduler scheduler = new HyperionScheduler(4)) {
            int taskCount = 500;
            CompletableFuture<Integer>[] futures = new CompletableFuture[taskCount];
            for (int i = 0; i < taskCount; i++) {
                int val = i;
                futures[i] = scheduler.submit(() -> val * val);
            }
            for (int i = 0; i < taskCount; i++) {
                assertEquals(i * i, futures[i].get());
            }
        }
    }

    @Test
    void schedulerPropagatesExceptions() throws Exception {
        try (HyperionScheduler scheduler = new HyperionScheduler(2)) {
            CompletableFuture<Integer> future = scheduler.submit(() -> {
                throw new RuntimeException("boom");
            });
            var ex = assertThrows(java.util.concurrent.ExecutionException.class, future::get);
            assertEquals("boom", ex.getCause().getMessage());
        }
    }

    @Test
    void singleWorkerSchedulerStillWorks() throws Exception {
        try (HyperionScheduler scheduler = new HyperionScheduler(1)) {
            CompletableFuture<Integer> f = scheduler.submit(() -> 42);
            assertEquals(42, f.get());
        }
    }
}
