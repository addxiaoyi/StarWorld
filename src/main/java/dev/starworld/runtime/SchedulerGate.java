package dev.starworld.runtime;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Explicit routing boundary for future Folia-compatible StarCore. Never access a player from global regions. */
public final class SchedulerGate {
    private final JavaPlugin plugin;
    private final boolean folia;
    public SchedulerGate(JavaPlugin plugin){this.plugin=plugin;this.folia=isFolia();}
    public static boolean isFolia(){try{Class.forName("io.papermc.paper.threadedregions.RegionizedServer");return true;}catch(ClassNotFoundException ignored){return false;}}
    public void global(Runnable task){
        if(!plugin.isEnabled())return;
        if(folia)Bukkit.getGlobalRegionScheduler().execute(plugin,task);
        else if(Bukkit.isPrimaryThread())task.run();else Bukkit.getScheduler().runTask(plugin,task);
    }
    public void player(Player player,Runnable task,Runnable retired){
        if(!plugin.isEnabled())return;
        if(folia){if(!player.getScheduler().execute(plugin,task,retired,1))retired.run();}
        else global(()->{if(player.isOnline())task.run();else retired.run();});
    }
    public void region(Location location,Runnable task){
        if(!plugin.isEnabled())return;
        if(folia)Bukkit.getRegionScheduler().execute(plugin,location,task);else global(task);
    }
}
