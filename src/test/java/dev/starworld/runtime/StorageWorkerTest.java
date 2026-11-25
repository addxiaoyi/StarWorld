package dev.starworld.runtime;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class StorageWorkerTest {
    @Test void callersCannotForgeCompletionOrReleaseQueuedStorageCapacity() throws Exception {
        StorageWorker worker = new StorageWorker(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean completedWorkRan = new AtomicBoolean();
        try {
            CompletableFuture<Integer> running = worker.submit(() -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int attempt = 0; attempt < 100; attempt++) {
                CompletableFuture<Boolean> pending = worker.submit(() -> {
                    completedWorkRan.set(true);
                    return true;
                });
                assertEquals(1, worker.queuedTasks());
                assertFalse(pending.completeExceptionally(new java.util.concurrent.TimeoutException("Caller timed out")));
                assertFalse(pending.complete(false));
                assertThrows(UnsupportedOperationException.class, () -> pending.obtrudeValue(false));
                assertEquals(1, worker.queuedTasks());
                assertTrue(pending.cancel(false));
                assertEquals(0, worker.queuedTasks());
            }
            CompletableFuture<Integer> following = worker.submit(() -> 2);
            release.countDown();
            assertEquals(1, running.get(2, TimeUnit.SECONDS));
            assertEquals(2, following.get(2, TimeUnit.SECONDS));
            assertFalse(completedWorkRan.get());
        } finally {
            release.countDown();
            assertTrue(worker.shutdown(2, 1, TimeUnit.SECONDS));
        }
    }

    @Test void timeoutCancelsOnlyQueuedStorageAndCompleteOnTimeoutCannotForgeItsResult() throws Exception {
        StorageWorker worker = new StorageWorker(2);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean timedOutWorkRan = new AtomicBoolean();
        try {
            CompletableFuture<Integer> running = worker.submit(() -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));

            CompletableFuture<Boolean> timedOut = worker.submit(() -> {
                timedOutWorkRan.set(true);
                return true;
            });
            CompletableFuture<Boolean> timeoutView = timedOut.orTimeout(10, TimeUnit.MILLISECONDS);
            assertNotSame(timedOut, timeoutView);
            assertInstanceOf(java.util.concurrent.TimeoutException.class,
                    assertThrows(ExecutionException.class, () -> timeoutView.get(1, TimeUnit.SECONDS)).getCause());
            assertFalse(timedOut.isDone());
            assertEquals(1, worker.queuedTasks());
            assertTrue(timedOut.cancel(false));
            assertEquals(0, worker.queuedTasks());

            CompletableFuture<Boolean> actual = worker.submit(() -> true);
            CompletableFuture<Boolean> fallback = actual.completeOnTimeout(false, 10, TimeUnit.MILLISECONDS);
            assertFalse(fallback.get(1, TimeUnit.SECONDS));
            assertFalse(actual.isDone());
            assertEquals(1, worker.queuedTasks());
            assertTrue(actual.cancel(false));
            assertFalse(timedOutWorkRan.get());

            release.countDown();
            assertEquals(1, running.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            assertTrue(worker.shutdown(2, 1, TimeUnit.SECONDS));
        }
    }

    @Test void cancelledQueuedWorkReleasesCapacityWhileStorageIsBlocked() throws Exception {
        StorageWorker worker = new StorageWorker(1);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean cancelledWorkRan = new AtomicBoolean();
        try {
            CompletableFuture<Integer> running = worker.submit(() -> {
                started.countDown();
                release.await();
                return 1;
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            for (int attempt = 0; attempt < 1000; attempt++) {
                CompletableFuture<Boolean> cancelled = worker.submit(() -> {
                    cancelledWorkRan.set(true);
                    return true;
                });
                assertFalse(cancelled.isDone());
                assertEquals(1, worker.queuedTasks());
                assertTrue(cancelled.cancel(false));
                assertEquals(0, worker.queuedTasks());
            }
            CompletableFuture<Integer> following = worker.submit(() -> 2);
            release.countDown();
            assertEquals(1, running.get(2, TimeUnit.SECONDS));
            assertEquals(2, following.get(2, TimeUnit.SECONDS));
            assertFalse(cancelledWorkRan.get());
        } finally {
            release.countDown();
            assertTrue(worker.shutdown(2, 1, TimeUnit.SECONDS));
        }
    }

    @Test void queueSaturationRejectsRepeatedOverloadWithoutGrowingPendingStorageFutures() throws Exception {
        StorageWorker worker = new StorageWorker();
        CountDownLatch runningStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean rejectedWorkRan = new AtomicBoolean();
        List<CompletableFuture<Integer>> queued = new ArrayList<>();
        try {
            CompletableFuture<Integer> running = worker.submit(() -> {
                runningStarted.countDown();
                release.await();
                return -1;
            });
            assertTrue(runningStarted.await(2, TimeUnit.SECONDS));
            for (int value = 0; value < StorageWorker.DEFAULT_QUEUE_CAPACITY; value++) {
                int expected = value;
                queued.add(worker.submit(() -> expected));
            }
            for (int attempt = 0; attempt < 10_000; attempt++) {
                CompletableFuture<Integer> rejected = worker.submit(() -> { rejectedWorkRan.set(true); return 4; });
                assertEquals(StorageWorker.DEFAULT_QUEUE_CAPACITY, worker.queuedTasks());
                assertInstanceOf(RejectedExecutionException.class,
                        assertThrows(ExecutionException.class, () -> rejected.get(1, TimeUnit.SECONDS)).getCause());
            }
            assertFalse(rejectedWorkRan.get());

            release.countDown();
            assertEquals(-1, running.get(1, TimeUnit.SECONDS));
            for (int value = 0; value < queued.size(); value++)
                assertEquals(value, queued.get(value).get(1, TimeUnit.SECONDS));
            assertTrue(worker.shutdown(1, 1, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            worker.shutdown(1, 1, TimeUnit.SECONDS);
        }
    }

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

    @Test void fatalTaskErrorFailsItsFutureAndAllowsTheExecutorToReplaceItsWorker() throws Exception {
        StorageWorker worker = new StorageWorker();
        try {
            CompletableFuture<Integer> failed = worker.submit(() -> { throw new AssertionError("fatal task failure"); });
            CompletableFuture<Integer> following = worker.submit(() -> 12);

            assertInstanceOf(AssertionError.class,
                    assertThrows(ExecutionException.class, () -> failed.get(2, TimeUnit.SECONDS)).getCause());
            assertEquals(12, following.get(2, TimeUnit.SECONDS));
        } finally {
            assertTrue(worker.shutdown(2, 1, TimeUnit.SECONDS));
        }
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
            assertFalse(running.cancel(false), "A running storage operation cannot be cancelled safely");
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
