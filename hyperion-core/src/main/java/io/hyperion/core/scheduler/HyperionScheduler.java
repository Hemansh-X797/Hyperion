package io.hyperion.core.scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An asynchronous task submission engine on real Java Virtual Threads
 * (Project Loom), balancing work across N worker threads' own
 * {@link WorkStealingDeque}s. Every worker owns exactly one deque and only
 * ever pushes/pops its own; when a worker's deque runs dry it steals from a
 * randomly chosen sibling — the textbook work-stealing load-balancing
 * pattern, here backing real Hyperion kernel dispatch (Phase 5 GPU calls,
 * Phase 7 SIMD batches) rather than a toy example.
 */
public final class HyperionScheduler implements AutoCloseable {

    private record Task<T>(Callable<T> work, CompletableFuture<T> future) {
        @SuppressWarnings("unchecked")
        void run() {
            try {
                Object result = work.call();
                ((CompletableFuture<Object>) (CompletableFuture<?>) future).complete(result);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }
    }

    private final List<WorkStealingDeque<Task<?>>> deques;
    private final List<Thread> workers;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicInteger roundRobin = new AtomicInteger(0);
    private final ThreadLocal<Integer> ownerIndex = new ThreadLocal<>();

    public HyperionScheduler(int workerCount) {
        if (workerCount < 1) throw new IllegalArgumentException("workerCount must be >= 1");
        this.deques = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) deques.add(new WorkStealingDeque<>());

        this.workers = new ArrayList<>(workerCount);
        for (int i = 0; i < workerCount; i++) {
            final int idx = i;
            Thread worker = Thread.ofVirtual().name("hyperion-worker-" + i).start(() -> workerLoop(idx));
            workers.add(worker);
        }
    }

    public int workerCount() {
        return deques.size();
    }

    /**
     * Submits work for asynchronous execution. If called from inside one of
     * this scheduler's own worker threads, the task is pushed onto that
     * worker's own deque (cheap, no contention); otherwise it's handed to a
     * round-robin-selected worker's deque (external submission).
     */
    public <T> CompletableFuture<T> submit(Callable<T> work) {
        if (shutdown.get()) {
            CompletableFuture<T> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Scheduler is shut down"));
            return failed;
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Task<T> task = new Task<>(work, future);
        Integer mine = ownerIndex.get();
        int target = (mine != null) ? mine : Math.floorMod(roundRobin.getAndIncrement(), deques.size());
        deques.get(target).pushBottom(task);
        return future;
    }

    private void workerLoop(int myIndex) {
        ownerIndex.set(myIndex);
        WorkStealingDeque<Task<?>> mine = deques.get(myIndex);
        int idleSpins = 0;

        while (true) {
            Task<?> task = mine.popBottom();
            if (task == null) {
                task = trySteal(myIndex);
            }
            if (task != null) {
                idleSpins = 0;
                task.run();
                continue;
            }
            if (shutdown.get() && mine.isEmpty() && allDequesEmpty()) {
                return; // graceful drain-then-exit
            }
            idleSpins++;
            backoff(idleSpins);
        }
    }

    private Task<?> trySteal(int myIndex) {
        int n = deques.size();
        if (n == 1) return null;
        int start = ThreadLocalRandom.current().nextInt(n);
        for (int i = 0; i < n; i++) {
            int victim = Math.floorMod(start + i, n);
            if (victim == myIndex) continue;
            Task<?> stolen = deques.get(victim).steal();
            if (stolen != null) return stolen;
        }
        return null;
    }

    private boolean allDequesEmpty() {
        for (WorkStealingDeque<Task<?>> d : deques) {
            if (!d.isEmpty()) return false;
        }
        return true;
    }

    private static void backoff(int idleSpins) {
        if (idleSpins < 100) {
            Thread.onSpinWait();
        } else {
            try {
                Thread.sleep(0, 200_000); // 200 microseconds — keeps idle workers from burning a whole core
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Signals workers to drain remaining queued work and exit; blocks until all have joined. */
    public void shutdown() {
        shutdown.set(true);
        for (Thread w : workers) {
            try {
                w.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        shutdown();
    }
}
