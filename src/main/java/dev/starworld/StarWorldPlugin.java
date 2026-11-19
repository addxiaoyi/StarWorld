package dev.starworld;

import dev.starworld.api.StarWorldApi;
import dev.starworld.config.ConfigLoader;
import dev.starworld.domain.Definitions;
import dev.starworld.integration.*;
import dev.starworld.persistence.WorldStore;
import dev.starworld.runtime.*;
import dev.starworld.ui.*;
import java.io.File;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class StarWorldPlugin extends JavaPlugin {
    private WorldRuntime runtime;
    @Override public void onEnable(){
        if(SchedulerGate.isFolia()){
            getLogger().severe("当前审计基线 StarCore 城市/科技服务尚不具备 Folia 线程契约。为保护国家权威状态，StarWorld 0.1.0 仅启用于 Paper 1.21.11。详见 docs/architecture.md。");
            Bukkit.getPluginManager().disablePlugin(this);return;
        }
        WorldStore store=null;
        try{
            saveDefaultConfig();if(!new File(getDataFolder(),"definitions.yml").exists())saveResource("definitions.yml",false);
            Definitions definitions=ConfigLoader.load(new File(getDataFolder(),"config.yml"),new File(getDataFolder(),"definitions.yml"));reloadConfig();
            CustomItems items=new CustomItems(this);items.validate(ConfigLoader.items(definitions));
            CoreBridge core=new StarCoreBridge(this);
            store=new WorldStore(new File(getDataFolder(),"starworld.db").toPath(),definitions.stockLimit());
            runtime=new WorldRuntime(this,definitions,core,items,store);
            CityMenu menu=new CityMenu(runtime,getConfig().getString("gui.title","StarWorld · 城市产业"));
            WorldCommand command=new WorldCommand(runtime,menu);
            java.util.Objects.requireNonNull(getCommand("starworld")).setExecutor(command);getCommand("starworld").setTabCompleter(command);
            Bukkit.getPluginManager().registerEvents(menu,this);
            Bukkit.getServicesManager().register(StarWorldApi.class,runtime,this,ServicePriority.Normal);
            Bukkit.getScheduler().runTaskTimer(this,runtime::tick,40L,40L);
            runtime.integrationStatus().forEach((k,v)->getLogger().info(k+": "+v));
            getLogger().info("StarWorld ready: 6 regions / 3 industry chains / transactional schema "+store.schemaVersion());
            runtime.pending().thenAccept(list->{if(!list.isEmpty())getLogger().warning("发现 "+list.size()+" 笔 PREPARED 物品操作。不会自动重放；管理员请 /sw pending 并核对。");});
        }catch(Exception e){
            getLogger().log(java.util.logging.Level.SEVERE,"StarWorld startup failed; saved state has not been reset",e);
            if(runtime==null && store!=null)try{store.close();}catch(Exception ignored){}
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }
    @Override public void onDisable(){
        Bukkit.getScheduler().cancelTasks(this);Bukkit.getServicesManager().unregisterAll(this);
        if(runtime!=null)try{runtime.close();}catch(Exception e){getLogger().log(java.util.logging.Level.SEVERE,"Could not flush StarWorld storage",e);}finally{runtime=null;}
    }
}
