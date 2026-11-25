package dev.starworld.runtime;

import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SchedulerGateTest {
    @Test void primaryCallbackReturnsItsActualValueAndFailure() throws Exception {
        try(Fixture f=new Fixture(true,false)){
            assertEquals(7,f.gate.globalFuture(()->7).get(1,TimeUnit.SECONDS));
            SQLException failure=new SQLException("capture failed");
            assertSame(failure,failed(f.gate.globalFuture(()->{throw failure;})));
            assertTrue(f.callbacks.isEmpty());verifyNoInteractions(f.paper,f.regions);
        }
    }

    @Test void queuedCallbackRunsAtMostOnceAndPropagatesItsFailure() throws Exception {
        try(Fixture f=new Fixture(false,false)){
            AtomicInteger calls=new AtomicInteger();
            var result=f.gate.globalFuture(calls::incrementAndGet);Runnable callback=f.callbacks.remove();
            assertFalse(result.isDone());callback.run();callback.run();
            assertEquals(1,result.get(1,TimeUnit.SECONDS));assertEquals(1,calls.get());
            AssertionError failure=new AssertionError("callback failed");
            var rejected=f.gate.globalFuture(()->{throw failure;});f.callbacks.remove().run();
            assertSame(failure,failed(rejected));
        }
    }

    @Test void disabledPluginRejectsRatherThanDroppingFuture() throws Exception {
        try(Fixture f=new Fixture(false,false)){
            f.enabled.set(false);AtomicBoolean ran=new AtomicBoolean();
            assertInstanceOf(IllegalStateException.class,failed(f.gate.globalFuture(()->{ran.set(true);return true;})));
            assertFalse(ran.get());assertTrue(f.callbacks.isEmpty());
            f.bukkit.verifyNoInteractions();verifyNoInteractions(f.paper,f.regions);
        }
    }

    @Test void schedulerRejectionAfterEnabledCheckCompletesFuture() throws Exception {
        try(Fixture f=new Fixture(false,false)){
            IllegalPluginAccessException failure=new IllegalPluginAccessException("disabled during submission");
            doThrow(failure).when(f.paper).runTask(eq(f.plugin),any(Runnable.class));
            AtomicBoolean ran=new AtomicBoolean();
            var result=assertDoesNotThrow(()->f.gate.globalFuture(()->{ran.set(true);return true;}));
            assertSame(failure,failed(result));f.gate.close();assertSame(failure,failed(result));assertFalse(ran.get());
        }
    }

    @Test void closeRetiresAcceptedCallbacksEvenWhenPlatformNeverInvokesThem() throws Exception {
        try(Fixture f=new Fixture(false,false)){
            AtomicInteger calls=new AtomicInteger();var first=f.gate.globalFuture(calls::incrementAndGet);var second=f.gate.globalFuture(calls::incrementAndGet);
            f.gate.close();f.gate.close();
            assertInstanceOf(IllegalStateException.class,failed(first));assertInstanceOf(IllegalStateException.class,failed(second));
            clearInvocations(f.plugin,f.paper,f.regions);f.bukkit.clearInvocations();
            f.callbacks.forEach(Runnable::run);
            assertEquals(0,calls.get());verifyNoInteractions(f.plugin,f.paper,f.regions);f.bukkit.verifyNoInteractions();
        }
    }

    @Test void closeDoesNotCompleteOrInterruptCallbackWhichAlreadyStarted() throws Exception {
        try(Fixture f=new Fixture(false,false)){
            CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);AtomicBoolean interrupted=new AtomicBoolean();
            var result=f.gate.globalFuture(()->{started.countDown();try{release.await();}catch(InterruptedException failure){interrupted.set(true);throw failure;}return 42;});
            Thread callback=new Thread(f.callbacks.remove(),"scheduler-gate-test");
            try{
                callback.start();assertTrue(started.await(2,TimeUnit.SECONDS));f.gate.close();
                assertFalse(result.isDone(),"A running callback must retain its actual outcome");assertFalse(interrupted.get());
                release.countDown();assertEquals(42,result.get(2,TimeUnit.SECONDS));
            }finally{release.countDown();callback.join(2000);}
            assertFalse(callback.isAlive());
        }
    }

    @Test void registrationRacingCloseCannotMissRetirement() throws Exception {
        JavaPlugin plugin=mock(JavaPlugin.class);BukkitScheduler scheduler=mock(BukkitScheduler.class);when(plugin.isEnabled()).thenReturn(true);
        SchedulerGate gate=new SchedulerGate(plugin,false);CountDownLatch submitting=new CountDownLatch(1),release=new CountDownLatch(1),closing=new CountDownLatch(1);
        AtomicReference<Runnable> queued=new AtomicReference<>();AtomicReference<CompletableFuture<Integer>> result=new AtomicReference<>();AtomicReference<Throwable> submissionFailure=new AtomicReference<>();AtomicBoolean ran=new AtomicBoolean();
        doAnswer(call->{queued.set(call.getArgument(1,Runnable.class));submitting.countDown();if(!release.await(2,TimeUnit.SECONDS))throw new IllegalStateException("submission test timed out");return null;}).when(scheduler).runTask(eq(plugin),any(Runnable.class));
        Thread submitter=new Thread(()->{
            try(MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class)){
                bukkit.when(Bukkit::isPrimaryThread).thenReturn(false);bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
                result.set(gate.globalFuture(()->{ran.set(true);return 9;}));
            }catch(Throwable failure){submissionFailure.set(failure);}
        },"scheduler-gate-submit-test");
        Thread closer=new Thread(()->{closing.countDown();gate.close();},"scheduler-gate-close-test");
        try{
            submitter.start();assertTrue(submitting.await(2,TimeUnit.SECONDS));closer.start();assertTrue(closing.await(2,TimeUnit.SECONDS));
            release.countDown();submitter.join(2000);closer.join(2000);assertFalse(submitter.isAlive());assertFalse(closer.isAlive());assertNull(submissionFailure.get());
            assertNotNull(result.get());assertInstanceOf(IllegalStateException.class,failed(result.get()));queued.get().run();assertFalse(ran.get());
        }finally{release.countDown();submitter.join(2000);if(closer.getState()!=Thread.State.NEW)closer.join(2000);gate.close();}
    }

    @Test void closeDoesNotFabricateCompletionForStorageStartedByCallback() throws Exception {
        try(Fixture f=new Fixture(false,false)){
            CompletableFuture<String> storage=new CompletableFuture<>();
            var result=f.gate.globalFuture(()->storage).thenCompose(future->future);f.callbacks.remove().run();
            f.gate.close();assertFalse(result.isDone());storage.complete("committed");
            assertEquals("committed",result.get(1,TimeUnit.SECONDS));
        }
    }

    @Test void allDispatchEntrypointsAfterCloseAvoidBukkitAndPlayerState() throws Exception {
        for(boolean folia:new boolean[]{false,true})try(Fixture f=new Fixture(false,folia)){
            f.gate.close();clearInvocations(f.plugin,f.paper,f.regions);f.bukkit.clearInvocations();
            Player player=mock(Player.class);Location location=mock(Location.class);AtomicBoolean ran=new AtomicBoolean();
            f.gate.global(()->ran.set(true));f.gate.player(player,()->ran.set(true),()->ran.set(true));f.gate.region(location,()->ran.set(true));
            assertInstanceOf(IllegalStateException.class,failed(f.gate.globalFuture(()->{ran.set(true);return true;})));
            assertFalse(ran.get());verifyNoInteractions(f.plugin,f.paper,f.regions,player,location);f.bukkit.verifyNoInteractions();
        }
    }

    @Test void fireAndForgetCallbacksKeepSilentRetirementAndOfflinePlayerFallback() {
        try(Fixture f=new Fixture(false,false)){
            Player player=mock(Player.class);AtomicInteger ran=new AtomicInteger(),retired=new AtomicInteger();
            f.gate.player(player,ran::incrementAndGet,retired::incrementAndGet);f.callbacks.remove().run();
            assertEquals(0,ran.get());assertEquals(1,retired.get());
            f.gate.global(ran::incrementAndGet);f.gate.player(player,ran::incrementAndGet,retired::incrementAndGet);
            f.gate.close();clearInvocations(player);f.callbacks.forEach(Runnable::run);
            assertEquals(0,ran.get());assertEquals(1,retired.get());verifyNoInteractions(player);
        }
    }

    @Test void foliaGlobalCallbacksUseGlobalSchedulerAndRetireOnClose() throws Exception {
        try(Fixture f=new Fixture(false,true)){
            var completed=f.gate.globalFuture(()->"global");f.callbacks.remove().run();assertEquals("global",completed.get(1,TimeUnit.SECONDS));
            AtomicBoolean ran=new AtomicBoolean();var queued=f.gate.globalFuture(()->{ran.set(true);return true;});f.gate.close();
            assertInstanceOf(IllegalStateException.class,failed(queued));f.callbacks.remove().run();assertFalse(ran.get());
            verify(f.regions,times(2)).execute(eq(f.plugin),any(Runnable.class));verifyNoInteractions(f.paper);
        }
    }

    private static Throwable failed(CompletableFuture<?> future) throws Exception {
        return assertThrows(ExecutionException.class,()->future.get(1,TimeUnit.SECONDS)).getCause();
    }
    private static final class Fixture implements AutoCloseable {
        final JavaPlugin plugin=mock(JavaPlugin.class);
        final BukkitScheduler paper=mock(BukkitScheduler.class);
        final GlobalRegionScheduler regions=mock(GlobalRegionScheduler.class);
        final Queue<Runnable> callbacks=new ArrayDeque<>();
        final AtomicBoolean enabled=new AtomicBoolean(true);
        final MockedStatic<Bukkit> bukkit=mockStatic(Bukkit.class);
        final SchedulerGate gate;
        Fixture(boolean primary,boolean folia){
            when(plugin.isEnabled()).thenAnswer(call->enabled.get());
            bukkit.when(Bukkit::isPrimaryThread).thenReturn(primary);bukkit.when(Bukkit::getScheduler).thenReturn(paper);bukkit.when(Bukkit::getGlobalRegionScheduler).thenReturn(regions);
            doAnswer(call->{callbacks.add(call.getArgument(1,Runnable.class));return null;}).when(paper).runTask(eq(plugin),any(Runnable.class));
            doAnswer(call->{callbacks.add(call.getArgument(1,Runnable.class));return null;}).when(regions).execute(eq(plugin),any(Runnable.class));
            gate=new SchedulerGate(plugin,folia);
        }
        @Override public void close(){try{gate.close();}finally{bukkit.close();}}
    }
}
