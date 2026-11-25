package dev.starworld.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicit routing boundary for future Folia-compatible StarCore. Never access a player from global regions. */
public final class SchedulerGate implements AutoCloseable {
    private final JavaPlugin plugin;
    private final boolean folia;
    private final Object lifecycle = new Object();
    private final Set<Pending<?>> waiting = new HashSet<>();
    private volatile boolean closed;

    public SchedulerGate(JavaPlugin plugin){this(plugin,isFolia());}
    SchedulerGate(JavaPlugin plugin,boolean folia){this.plugin=Objects.requireNonNull(plugin);this.folia=folia;}
    public static boolean isFolia(){try{Class.forName("io.papermc.paper.threadedregions.RegionizedServer");return true;}catch(ClassNotFoundException ignored){return false;}}

    /** Fire-and-forget UI/event dispatch retains its existing silent retirement semantics. */
    public void global(Runnable task){
        Runnable guarded=guard(task);boolean inline=false;
        synchronized(lifecycle){
            if(closed||!plugin.isEnabled())return;
            if(folia)Bukkit.getGlobalRegionScheduler().execute(plugin,guarded);
            else if(Bukkit.isPrimaryThread())inline=true;
            else Bukkit.getScheduler().runTask(plugin,guarded);
        }
        if(inline)guarded.run();
    }

    /** A queued callback is retired explicitly even when the platform cancels it without invoking it. */
    public <T> CompletableFuture<T> globalFuture(Callable<T> task){
        Pending<T> pending=new Pending<>(Objects.requireNonNull(task));
        Throwable failure=null;boolean inline=false;boolean rejected=false;
        synchronized(lifecycle){
            if(closed)failure=stopping();
            else{
                waiting.add(pending);
                try{
                    if(!plugin.isEnabled())failure=stopping();
                    else if(folia)Bukkit.getGlobalRegionScheduler().execute(plugin,pending);
                    else if(Bukkit.isPrimaryThread())inline=true;
                    else Bukkit.getScheduler().runTask(plugin,pending);
                }catch(RuntimeException error){failure=error;}
            }
            if(failure!=null&&pending.state==State.WAITING){
                pending.state=State.RETIRED;waiting.remove(pending);rejected=true;
            }
        }
        // Completion may run arbitrary continuations, so never complete while holding the lifecycle lock.
        if(rejected)pending.result.completeExceptionally(failure);
        if(inline)pending.run();
        return pending.result;
    }

    public void player(Player player,Runnable task,Runnable retired){
        if(!folia){global(()->{if(player.isOnline())task.run();else retired.run();});return;}
        Runnable abandoned=guard(retired);boolean rejected=false;
        synchronized(lifecycle){
            if(closed||!plugin.isEnabled())return;
            if(!player.getScheduler().execute(plugin,guard(task),abandoned,1))rejected=true;
        }
        if(rejected)abandoned.run();
    }

    public void region(Location location,Runnable task){
        if(!folia){global(task);return;}
        synchronized(lifecycle){
            if(closed||!plugin.isEnabled())return;
            Bukkit.getRegionScheduler().execute(plugin,location,guard(task));
        }
    }

    @Override public void close(){
        List<Pending<?>> retired=new ArrayList<>();
        synchronized(lifecycle){
            if(closed)return;
            closed=true;
            for(Pending<?> pending:waiting)if(pending.state==State.WAITING){pending.state=State.RETIRED;retired.add(pending);}
            waiting.clear();
        }
        for(Pending<?> pending:retired)pending.result.completeExceptionally(stopping());
    }

    private Runnable guard(Runnable task){Objects.requireNonNull(task);return ()->{if(!closed)task.run();};}
    private static IllegalStateException stopping(){return new IllegalStateException("StarWorld scheduler is stopping");}
    private enum State { WAITING, RUNNING, RETIRED }
    private final class Pending<T> implements Runnable {
        private final Callable<T> task;
        private final CompletableFuture<T> result=new CompletableFuture<>();
        private State state=State.WAITING;
        private Pending(Callable<T> task){this.task=task;}
        @Override public void run(){
            synchronized(lifecycle){
                if(state!=State.WAITING)return;
                state=State.RUNNING;waiting.remove(this);
            }
            try{result.complete(task.call());}catch(Throwable failure){result.completeExceptionally(failure);}
            finally{synchronized(lifecycle){state=State.RETIRED;}}
        }
    }
}
