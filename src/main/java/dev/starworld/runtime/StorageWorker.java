package dev.starworld.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Serial storage execution. A discarded queue entry must never leave its caller waiting forever. */
final class StorageWorker {
    static final int DEFAULT_QUEUE_CAPACITY = 256;

    @FunctionalInterface
    interface Task<T> { T run() throws Exception; }

    private final ThreadPoolExecutor executor;
    private final int queueCapacity;

    StorageWorker() { this(DEFAULT_QUEUE_CAPACITY); }

    StorageWorker(int queueCapacity) {
        if (queueCapacity < 1) throw new IllegalArgumentException("Storage queue capacity must be positive");
        this.queueCapacity = queueCapacity;
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(queueCapacity), work -> {
                    Thread thread = new Thread(work, "StarWorld-storage");
                    thread.setDaemon(true);
                    return thread;
                });
    }

    <T> CompletableFuture<T> submit(Task<T> task) {
        Pending<T> pending = new Pending<>(Objects.requireNonNull(task));
        pending.result.whenComplete((value, failure) -> {
            executor.remove(pending);
        });
        try {
            executor.execute(pending);
        } catch (RejectedExecutionException stopped) {
            // Includes both queue saturation and the race with executor shutdown.
            pending.rejectBeforeStart(executor.isShutdown()
                    ? new IllegalStateException("StarWorld storage stopped before the queued task started")
                    : new RejectedExecutionException("StarWorld storage queue is full (capacity " + queueCapacity + ")", stopped));
        }
        return pending.result;
    }

    int queuedTasks() { return executor.getQueue().size(); }

    /** False means a running task still owns storage: its connection must remain open. */
    boolean shutdown(long gracefulTimeout, long forcedTimeout, TimeUnit unit) throws InterruptedException {
        Objects.requireNonNull(unit);
        if (gracefulTimeout < 0 || forcedTimeout < 0) throw new IllegalArgumentException("Negative storage shutdown timeout");
        executor.shutdown();
        try {
            if (executor.awaitTermination(gracefulTimeout, unit)) return true;
            rejectQueued(executor.shutdownNow());
            return executor.awaitTermination(forcedTimeout, unit);
        } catch (InterruptedException interrupted) {
            rejectQueued(executor.shutdownNow());
            Thread.currentThread().interrupt();
            throw interrupted;
        }
    }

    private static void rejectQueued(List<Runnable> discarded) {
        for (Runnable task : discarded) {
            if (task instanceof Pending<?> pending)
                pending.rejectBeforeStart(new IllegalStateException("StarWorld storage stopped before the queued task started"));
        }
    }

    private static final class Pending<T> implements Runnable {
        private final Task<T> task;
        private final StorageFuture<T> result = new StorageFuture<>(this);
        private State state = State.QUEUED;

        private Pending(Task<T> task) { this.task = task; }

        @Override public void run() {
            synchronized (this) {
                if (state != State.QUEUED) return;
                state = State.RUNNING;
            }
            try {
                result.completeFromWorker(task.run());
            } catch (Error fatal) {
                result.failFromWorker(fatal);
                throw fatal;
            } catch (Exception error) {
                result.failFromWorker(new CompletionException(error));
            } finally {
                synchronized (this) { state = State.FINISHED; }
            }
        }

        private synchronized void rejectBeforeStart(RuntimeException failure) {
            if (state != State.QUEUED) return;
            state = State.FINISHED;
            result.failFromWorker(failure);
        }

        private synchronized boolean cancelIfQueued() {
            if (state != State.QUEUED) return false;
            state = State.FINISHED;
            return result.cancelFromWorker();
        }

        private enum State { QUEUED, RUNNING, FINISHED }
    }

    private static final class StorageFuture<T> extends CompletableFuture<T> {
        private final Pending<T> owner;

        private StorageFuture(Pending<T> owner) { this.owner = owner; }

        @Override public boolean complete(T value) { return false; }
        @Override public boolean completeExceptionally(Throwable failure) { return false; }
        @Override public void obtrudeValue(T value) {
            throw new UnsupportedOperationException("Storage worker owns completion");
        }
        @Override public void obtrudeException(Throwable failure) {
            throw new UnsupportedOperationException("Storage worker owns completion");
        }
        @Override public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier) {
            throw new UnsupportedOperationException("Storage worker owns completion");
        }
        @Override public CompletableFuture<T> completeAsync(Supplier<? extends T> supplier,
                                                             java.util.concurrent.Executor executor) {
            throw new UnsupportedOperationException("Storage worker owns completion");
        }
        @Override public CompletableFuture<T> completeOnTimeout(T value, long timeout, TimeUnit unit) {
            // A fallback belongs to the caller's view, never to the storage transaction.
            return super.copy().completeOnTimeout(value, timeout, unit);
        }
        @Override public boolean cancel(boolean mayInterruptIfRunning) {
            return owner.cancelIfQueued();
        }

        private boolean completeFromWorker(T value) { return super.complete(value); }
        private boolean failFromWorker(Throwable failure) { return super.completeExceptionally(failure); }
        private boolean cancelFromWorker() { return super.cancel(false); }

        @Override public CompletableFuture<T> orTimeout(long timeout, TimeUnit unit) {
            // Timing out a view cannot report a queued write as cancelled or completed.
            return super.copy().orTimeout(timeout, unit);
        }
    }
}
