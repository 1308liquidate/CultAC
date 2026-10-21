package ac.cult.cultac.utils.latency;

import ac.cult.cultac.checks.Check;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

public class CompensatedCameraEntity extends Check {
    private final ArrayDeque<PacketEntity> entities = new ArrayDeque<>(1);

    public CompensatedCameraEntity(CultPlayer player) {
        super(player);
        reset();
    }

    public void onSetCamera(int camera) {
        player.sendTransaction();

        player.latencyUtils.addRealTimeTaskNow(() -> {
            PacketEntity entity = player.compensatedEntities.getEntity(camera);
            if (entity != null) {
                entities.add(entity);
            }
        });

        player.latencyUtils.addRealTimeTaskNext(() -> {
            while (entities.size() > 1) {
                entities.poll();
            }

            if (entities.isEmpty()) {
                entities.add(player.compensatedEntities.getSelf());
            }
        });
    }

    public boolean isSelf() {
        PacketEntity self = player.compensatedEntities.getSelf();
        if (entities.size() == 1) return entities.peekFirst() == self;
        for (PacketEntity entity : entities) {
            if (entity != self) {
                return false;
            }
        }

        return true;
    }

    public List<PacketEntity> getPossibilities() {
        return new ArrayList<>(entities);
    }

    public void reset() {
        entities.clear();
        entities.add(player.compensatedEntities.getSelf());
    }
}
