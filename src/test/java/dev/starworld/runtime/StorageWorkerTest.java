package dev.starworld.runtime;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageWorkerTest {
    @Test void interruptedShutdownRejectsOnlyQueuedWorkAndPreservesCallerInterruption() throws Exception {
        StorageWorker worker = new StorageWorker();
        CountDownLatch runningStarted = new CountDownLatch(1), release = new CountDownLatch(1), closing = new CountDownLatch(1);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean(), queuedRan = new AtomicBoolean();
        Thread closer = new Thread(() -> {
            closing.countDown();
            try { worker.shutdown(30, 1, TimeUnit.SECONDS); }
            catch (Throwable failure) { closeFailure.set(failure); interrupted.set(Thread.currentThread().isInterrupted()); }
        });
        closer.setDaemon(true);
        try {
            CompletableFuture<Integer> running = worker.submit(() -> {
                runningStarted.countDown();
                for (;;) { try { release.await(); return 9; } catch (InterruptedException ignored) { } }
            });
            assertTrue(runningStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> queued = worker.submit(() -> { queuedRan.set(true); return true; });
            closer.start();assertTrue(closing.await(2, TimeUnit.SECONDS));closer.interrupt();closer.join(2000);
            assertFalse(closer.isAlive());assertInstanceOf(InterruptedException.class, closeFailure.get());assertTrue(interrupted.get());
            assertFalse(running.isDone());assertFalse(queuedRan.get());
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS)).getCause());
            release.countDown();assertEquals(9, running.get(2, TimeUnit.SECONDS));assertTrue(worker.shutdown(1, 1, TimeUnit.SECONDS));
        } finally {
            closer.interrupt();release.countDown();closer.join(2000);worker.shutdown(1, 1, TimeUnit.SECONDS);
        }
    }
    @Test void gracefulShutdownPreservesOrderAndCompletesEveryAcceptedFuture() throws Exception {
        StorageWorker worker = new StorageWorker();
        List<Integer> order = new ArrayList<>();
        CompletableFuture<Integer> first = worker.submit(() -> { order.add(1); return 1; });
        CompletableFuture<Integer> failed = worker.submit(() -> { throw new SQLException("failure stays in its own future"); });
        CompletableFuture<Integer> last = worker.submit(() -> { order.add(3); return 3; });
        assertTrue(worker.shutdown(2, 1, TimeUnit.SECONDS));
        assertEquals(1, first.get(1, TimeUnit.SECONDS));
        assertInstanceOf(SQLException.class, assertThrows(ExecutionException.class, () -> failed.get(1, TimeUnit.SECONDS)).getCause());
        assertEquals(3, last.get(1, TimeUnit.SECONDS));
        assertEquals(List.of(1, 3), order);
        CompletableFuture<Integer> rejected = assertDoesNotThrow(() -> worker.submit(() -> 4));
        assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
    }

    @Test void forcedShutdownCompletesDiscardedFuturesWithoutRunningTheirWork() throws Exception {
        StorageWorker worker = new StorageWorker();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            CompletableFuture<Boolean> running = worker.submit(() -> { started.countDown(); release.await(); return true; });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> queued = worker.submit(() -> { queuedRan.set(true); return true; });
            assertTrue(worker.shutdown(0, 2, TimeUnit.SECONDS));
            assertFalse(queuedRan.get());
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS)).getCause());
            assertInstanceOf(InterruptedException.class, assertThrows(ExecutionException.class, () -> running.get(1, TimeUnit.SECONDS)).getCause());
        } finally {
            release.countDown();
            worker.shutdown(1, 1, TimeUnit.SECONDS);
        }
    }

    @Test void uncooperativeRunningWorkIsNotReportedAsStoppedOrPrematurelyCompleted() throws Exception {
        StorageWorker worker = new StorageWorker();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean queuedRan = new AtomicBoolean();
        try {
            CompletableFuture<Integer> running = worker.submit(() -> {
                started.countDown();
                boolean released = false;
                while (!released) {
                    try { release.await(); released = true; }
                    catch (InterruptedException ignored) { /* Simulate a driver which has not retired yet. */ }
                }
                return 7;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            CompletableFuture<Boolean> queued = worker.submit(() -> { queuedRan.set(true); return true; });
            assertFalse(worker.shutdown(0, 10, TimeUnit.MILLISECONDS));
            assertFalse(running.isDone(), "A running write may still commit: do not fabricate its completion");
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class, () -> queued.get(1, TimeUnit.SECONDS)).getCause());
            assertFalse(queuedRan.get());
            release.countDown();
            assertEquals(7, running.get(2, TimeUnit.SECONDS));
            assertTrue(worker.shutdown(1, 1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            worker.shutdown(1, 1, TimeUnit.SECONDS);
        }
    }
}
