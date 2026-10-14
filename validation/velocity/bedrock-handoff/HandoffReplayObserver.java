package ac.cultac.bedrockreplay.capture;

import static ac.cultac.bedrockreplay.regression.JsonFields.*;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/** Passive replay observation, segmented at engine transfer because each generation has its own native user. */
public final class HandoffReplayObserver {
    private record Run(String id, String username, Path output, NativeReplayObservation observation) {}
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path directory;
    private final java.util.concurrent.ConcurrentMap<String, Run> runs = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<Path,String> lastRequests = new java.util.HashMap<>();
    private final java.util.Map<Path,String> channels = new java.util.HashMap<>();
    private ScheduledTask task;
    private VelocityCultAccess cult;

    @Inject
    public HandoffReplayObserver(ProxyServer proxy, Logger logger, @DataDirectory Path directory) {
        this.proxy = proxy;
        this.logger = logger;
        this.directory = directory;
    }

    @Subscribe(order = PostOrder.LAST)
    public void initialize(ProxyInitializeEvent event) throws Exception {
        Files.createDirectories(directory);
        cult = new VelocityCultAccess(proxy);
        cult.register("cultac.bedrock.movementDebug.active", name -> {
            Run run = runs.get(String.valueOf(name));
            return run != null && run.observation.active(name);
        });
        cult.register("cultac.bedrock.movementDebug.record", value -> {
            Run run = value instanceof Map<?,?> fields ? runs.get(String.valueOf(fields.get("username"))) : null;
            return run != null && run.observation.debug(value);
        });
        task = proxy.getScheduler().buildTask(this, this::poll).repeat(50, TimeUnit.MILLISECONDS).schedule();
        write(directory.resolve("ready.json"), Map.of("debugApi", true, "platform", "velocity"));
    }

    private synchronized void poll() {
        pollControl(directory);
        Path players = directory.resolve("players");
        if (Files.isDirectory(players)) try (var children=Files.list(players)) {
            children.filter(Files::isDirectory).forEach(this::pollControl);
        } catch (Exception error) { logger.error("Cannot read player control channels",error); }
        for (Run run : runs.values()) proxy.getPlayer(run.username).ifPresent(player -> {
            try { run.observation.poll(cult.player(player.getUniqueId())); }
            catch (Exception error) { run.observation.fail("Native player lookup failed: " + error); }
        });
    }

    private void pollControl(Path channel) {
        Path control = channel.resolve("control.json");
        if (!Files.isRegularFile(control)) return;
        String id = lastRequests.getOrDefault(channel, "");
        try {
            var request=read(control);
            id=string(request,"requestId");
            if (id.equals(lastRequests.get(channel))) return;
            lastRequests.put(channel,id);
            String operation=string(request,"operation");
            Run run = runs.get(channels.getOrDefault(channel,""));
            if (operation.equals("prepare")) {
                if (run!=null) throw new IllegalStateException("Previous observation segment is still active");
                String runId=string(request,"runId"), name=string(request,"username");
                Path output=Path.of(string(request,"artifacts"));
                var observer=new NativeReplayObservation(runId,name,output,logger::warn);
                var next=new Run(runId,name,output,observer);
                if (runs.putIfAbsent(name,next)!=null) throw new IllegalStateException("Duplicate player observation");
                channels.put(channel,name);
                write(output.resolve("prepared.json"),Map.of("runId",runId,"debugApi",true));
                if (proxy.getPlayer(name).isPresent()) marker(next,"joined.json");
            } else if (operation.equals("suspend") || operation.equals("finish")) {
                if (run==null || !run.id.equals(string(request,"runId")))
                    throw new IllegalArgumentException("Stale observation segment");
                if (operation.equals("finish") && proxy.getPlayer(run.username).isPresent())
                    throw new IllegalStateException("Client is still online");
                if (!run.observation.failure().isEmpty()) throw new IllegalStateException(run.observation.failure());
                run.observation.close();
                runs.remove(run.username,run);
            } else throw new IllegalArgumentException("Unknown control operation "+operation);
            write(channel.resolve("control-status.json"),Map.of("requestId",id,"ok",true));
        } catch (Exception error) {
            logger.error("Handoff observation failed",error);
            try { write(channel.resolve("control-status.json"),Map.of("requestId",id,"ok",false,"error",error.toString())); }
            catch (Exception failure) { logger.error("Cannot publish control failure",failure); }
        }
    }

    private void marker(Run run, String name) {
        if (run == null) return;
        try { write(run.output.resolve(name), Map.of("runId", run.id)); }
        catch (Exception error) { run.observation.fail("Lifecycle observation failed: " + error); }
    }

    @Subscribe
    public void connected(ServerPostConnectEvent event) {
        Run run = runs.get(event.getPlayer().getUsername());
        if (run != null) marker(run,"joined.json");
    }

    @Subscribe
    public void disconnected(DisconnectEvent event) {
        Run run = runs.get(event.getPlayer().getUsername());
        if (run != null) {
            run.observation.disconnected();
            marker(run,"disconnected.json");
        }
    }

    @Subscribe
    public synchronized void shutdown(ProxyShutdownEvent event) throws Exception {
        if (task != null) task.cancel();
        for (Run run:runs.values()) run.observation.close();
        runs.clear();
        Files.deleteIfExists(directory.resolve("ready.json"));
    }
}
