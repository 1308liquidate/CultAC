package ac.cultac.bedrockreplay.capture;

import static ac.cultac.bedrockreplay.regression.JsonFields.*;

import ac.cultac.bedrockreplay.regression.MovementScenario;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.spigotmc.event.player.PlayerSpawnLocationEvent;

/** One immutable terrain fixture per player, using the original world and loadout implementation. */
public final class HandoffReplayFixture extends JavaPlugin implements Listener {
    private record Fixture(String id,String username,World world,JsonObject initial,Path output) {}
    private final Map<String,Fixture> fixtures=new HashMap<>();
    private final Map<Path,String> lastRequests=new HashMap<>();

    @Override public void onEnable() {
        getDataFolder().mkdirs();
        Bukkit.getPluginManager().registerEvents(this,this);
        Bukkit.getScheduler().runTaskTimer(this,this::poll,1,1);
        try { write(getDataFolder().toPath().resolve("ready.json"),Map.of("schema","cultac-handoff-fixture/v1")); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void poll() {
        Path players=getDataFolder().toPath().resolve("players");
        if (!Files.isDirectory(players)) return;
        try (var directories=Files.list(players)) {
            directories.filter(Files::isDirectory).forEach(this::control);
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private void control(Path channel) {
        Path file=channel.resolve("control.json");
        if (!Files.isRegularFile(file)) return;
        String id=lastRequests.getOrDefault(channel,"");
        try {
            var request=read(file);id=string(request,"requestId");
            if (id.equals(lastRequests.get(channel))) return;
            lastRequests.put(channel,id);
            if (!string(request,"operation").equals("prepare")) throw new IllegalArgumentException("Unknown fixture operation");
            String name=string(request,"username"), run=string(request,"runId");
            if (!run.matches("[a-z0-9]{8,32}") || name.isBlank()) throw new IllegalArgumentException("Invalid run identity");
            Fixture previous=fixtures.get(name);
            if (Bukkit.getPlayerExact(name)!=null) throw new IllegalStateException("Previous client is online");
            if (previous!=null && (!previous.world.getPlayers().isEmpty() || !Bukkit.unloadWorld(previous.world,false)))
                throw new IllegalStateException("Previous fixture world is occupied");
            MovementScenario scenario;
            Thread thread=Thread.currentThread();ClassLoader original=thread.getContextClassLoader();
            try { thread.setContextClassLoader(getClass().getClassLoader());scenario=MovementScenario.load(Path.of(string(request,"scenario"))); }
            finally { thread.setContextClassLoader(original); }
            JsonObject initial=object(scenario.manifest(),"initialState");
            World world=StaticScenarioWorld.create(run,read(scenario.root().resolve("terrain.json")),initial);
            Path output=Path.of(string(request,"artifacts"));Files.createDirectories(output);
            Fixture fixture=new Fixture(run,name,world,initial,output);
            fixtures.put(name,fixture);
            write(output.resolve("prepared.json"),Map.of("runId",run,"world",world.getName()));
            write(channel.resolve("control-status.json"),Map.of("requestId",id,"ok",true));
        } catch (Exception | LinkageError error) {
            getLogger().warning("Fixture control failed: "+error);
            try { write(channel.resolve("control-status.json"),Map.of("requestId",id,"ok",false,"error",error.toString())); }
            catch (Exception failure) { throw new IllegalStateException(failure); }
        }
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void spawn(PlayerSpawnLocationEvent event) {
        Fixture fixture=fixtures.get(event.getPlayer().getName());
        if (fixture!=null) event.setSpawnLocation(StaticScenarioWorld.spawn(fixture.world,fixture.initial));
    }

    @EventHandler(priority=EventPriority.MONITOR)
    public void join(PlayerJoinEvent event) {
        Fixture fixture=fixtures.get(event.getPlayer().getName());
        if (fixture!=null) { StaticScenarioWorld.reset(event.getPlayer(),fixture.initial);marker(fixture,"joined.json"); }
    }

    @EventHandler public void quit(PlayerQuitEvent event) {
        Fixture fixture=fixtures.get(event.getPlayer().getName());
        if (fixture!=null) marker(fixture,"disconnected.json");
    }

    private void marker(Fixture fixture,String file) {
        try { write(fixture.output.resolve(file),Map.of("runId",fixture.id)); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
}
