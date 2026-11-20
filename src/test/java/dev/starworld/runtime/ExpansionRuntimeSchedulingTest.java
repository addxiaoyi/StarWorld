package dev.starworld.runtime;

import dev.starworld.config.ConfigLoader;
import dev.starworld.domain.Definitions;
import dev.starworld.integration.CoreBridge;
import dev.starworld.integration.CustomItems;
import dev.starworld.persistence.WorldStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExpansionRuntimeSchedulingTest {
    @TempDir Path temp;
    private Definitions definitions;

    @BeforeEach void prepare()throws Exception{
        for(String file:List.of("config.yml","definitions.yml","evolution.yml","exports.yml","logistics.yml"))try(var input=getClass().getResourceAsStream("/"+file)){Files.copy(Objects.requireNonNull(input),temp.resolve(file));}
        Files.writeString(temp.resolve("config.yml"),"config-version: 2\nsimulation:\n  event-chance: 0\n");
        try(var materials=mockStatic(Material.class)){
            Material block=mock(Material.class);when(block.isBlock()).thenReturn(true);materials.when(()->Material.matchMaterial(anyString())).thenReturn(block);
            definitions=ConfigLoader.load(temp.resolve("config.yml").toFile(),temp.resolve("definitions.yml").toFile(),temp.resolve("evolution.yml").toFile());
        }
    }

    @Test void retiredTransportResourceDecorationAndPaymentCallbacksReleaseExpansionTick()throws Exception{
        try(Fixture f=new Fixture()){
            List<CompletableFuture<Object>> waiting=new ArrayList<>();
            when(f.runtime.globalFuture(any())).thenAnswer(call->{CompletableFuture<Object> result=new CompletableFuture<>();waiting.add(result);return result;});
            f.expansion.tick();assertEquals(4,waiting.size());f.expansion.tick();assertEquals(4,waiting.size(),"An active pass must not overlap");
            for(int i=0;i<4;i++)waiting.get(i).completeExceptionally(new IllegalStateException("scheduler stopped"));
            f.expansion.tick();assertEquals(8,waiting.size(),"Retired callbacks must release the aggregate pass");
            for(int i=4;i<8;i++)waiting.get(i).completeExceptionally(new IllegalStateException("scheduler stopped"));
            verify(f.logger,times(2)).warning(contains("durable work retained"));verifyNoInteractions(f.core);
            assertTrue(f.store.query("SELECT value FROM extension_meta WHERE key='culture-trade-cursor'",row->row.getString(1)).isEmpty());
        }
    }

    @Test void retiredCultureCallbackReleasesAggregateWithoutAdvancingCursor()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicInteger calls=new AtomicInteger();List<CompletableFuture<Object>> culture=new ArrayList<>();
            when(f.runtime.globalFuture(any())).thenAnswer(call->{
                if(calls.incrementAndGet()%5==0){CompletableFuture<Object> result=new CompletableFuture<>();culture.add(result);return result;}
                try{return CompletableFuture.completedFuture(((Callable<?>)call.getArgument(0)).call());}catch(Throwable failure){return CompletableFuture.failedFuture(failure);}
            });
            f.expansion.tick();assertEquals(5,calls.get());assertEquals(1,culture.size());f.expansion.tick();assertEquals(5,calls.get());
            culture.getFirst().completeExceptionally(new IllegalStateException("scheduler stopped"));
            f.expansion.tick();assertEquals(10,calls.get());assertEquals(2,culture.size());
            culture.getLast().completeExceptionally(new IllegalStateException("scheduler stopped"));
            verify(f.logger,times(2)).warning(contains("durable work retained"));verifyNoInteractions(f.core);
            assertTrue(f.store.query("SELECT value FROM extension_meta WHERE key='culture-trade-cursor'",row->row.getString(1)).isEmpty());
        }
    }

    private final class Fixture implements AutoCloseable {
        final JavaPlugin plugin=mock(JavaPlugin.class);
        final WorldRuntime runtime=mock(WorldRuntime.class);
        final CoreBridge core=mock(CoreBridge.class);
        final Logger logger=mock(Logger.class);
        final WorldStore store;
        final ExpansionRuntime expansion;
        Fixture()throws Exception{
            Server server=mock(Server.class);when(plugin.getServer()).thenReturn(server);when(plugin.getLogger()).thenReturn(logger);when(server.isPrimaryThread()).thenReturn(true);when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
            when(runtime.currentPeriod()).thenReturn(10L);
            when(runtime.async(any())).thenAnswer(call->{try{return CompletableFuture.completedFuture(((WorldRuntime.Work<?>)call.getArgument(0)).run());}catch(Throwable failure){return CompletableFuture.failedFuture(failure);}});
            store=new WorldStore(temp.resolve("scheduler.db"),definitions.stockLimit());expansion=new ExpansionRuntime(plugin,runtime,definitions,core,mock(CustomItems.class),store,temp.toFile());clearInvocations(core);
        }
        @Override public void close()throws Exception{try{expansion.close();}finally{store.close();}}
    }
}
