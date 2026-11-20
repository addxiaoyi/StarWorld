package dev.starworld.runtime;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** Serial storage execution. A discarded queue entry must never leave its caller waiting forever. */
final class StorageWorker {
    @FunctionalInterface
    interface Task<T> { T run() throws Exception; }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(work -> {
        Thread thread = new Thread(work, "StarWorld-storage");
        thread.setDaemon(true);
        return thread;
    });

    <T> CompletableFuture<T> submit(Task<T> task) {
        Pending<T> pending = new Pending<>(Objects.requireNonNull(task));
        try {
            executor.execute(pending);
        } catch (RejectedExecutionException stopped) {
            // Includes the race between WorldRuntime's closed check and executor shutdown.
            pending.rejectBeforeStart();
        }
        return pending.result;
    }

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
            if (task instanceof Pending<?> pending) pending.rejectBeforeStart();
        }
    }

    private static final class Pending<T> implements Runnable {
        private final Task<T> task;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Pending(Task<T> task) { this.task = task; }

        @Override public void run() {
            if (result.isDone()) return;
            try {
                result.complete(task.run());
            } catch (Throwable error) {
                result.completeExceptionally(new CompletionException(error));
            }
        }

        private void rejectBeforeStart() {
            result.completeExceptionally(new IllegalStateException("StarWorld storage stopped before the queued task started"));
        }
    }
}
