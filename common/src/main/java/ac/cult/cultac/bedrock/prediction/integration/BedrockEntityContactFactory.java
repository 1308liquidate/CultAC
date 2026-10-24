package ac.cult.cultac.bedrock.prediction.integration;

import ac.cult.blocksim.entity.EntityTypeIds;
import ac.cult.cultac.bedrock.prediction.geometry.Vec3d;
import ac.cult.cultac.bedrock.prediction.geometry.WorldCollisionBox;
import ac.cult.cultac.bedrock.prediction.model.PlayerDimensionsState;
import ac.cult.cultac.bedrock.prediction.state.BedrockMovementState;
import ac.cult.cultac.bedrock.prediction.world.DolphinBoostState;
import ac.cult.cultac.bedrock.prediction.world.EntityContactState;
import ac.cult.cultac.bedrock.protocol.BedrockCoordinateFrame;
import ac.cult.cultac.checks.impl.prediction.SimulationContext;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.data.packetentity.PacketEntity;
import ac.cult.cultac.utils.data.packetentity.PacketEntityPlayer;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class BedrockEntityContactFactory {
    private static final float DOLPHIN_PROXIMITY_RANGE = 10.0F;

    BedrockEntityContactFactory() {}

    EntityContactState create(CultPlayer player, SimulationContext context) {
        DolphinBoostState dolphinBoostState = new DolphinBoostState(hasDolphinProximity(player, context));
        var entities = context.getEntities() != null ? context.getEntities() : player.compensatedEntities;
        boolean passenger = context.getVehicle() == null
                && entities != null
                && entities.getSelf().inVehicle();
        return new EntityContactState(
                dolphinBoostState,
                passenger,
                context.getVehicle() != null || passenger ? List.of() : spinAttackTargets(context, entities));
    }

    /**
     * The mobs a riptide spin attack may hit this tick: every living entity the client holds but a dead one, a
     * spectator or an untracked rider, in the box the client shows it in. Only a profile that is spinning or charging
     * a riptide release can spin this tick.
     */
    private static List<WorldCollisionBox> spinAttackTargets(SimulationContext context, CompensatedEntities entities) {
        if (entities == null || entities.entityMap.isEmpty()) return List.of();
        boolean maySpin = BedrockProfileState.profileEntries(context).stream()
                .map(BedrockProfileState.Entry::state)
                .anyMatch(state -> state.riptideSpinActive() || state.riptideChargeTicks() > 0L);
        if (!maySpin) return List.of();
        ArrayList<WorldCollisionBox> targets = new ArrayList<>();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity == null
                    || !entity.isLivingEntity()
                    || entity.isDead
                    || entities.isUntrackedRider(entity)
                    || entity instanceof PacketEntityPlayer remote
                            && remote.profile != null
                            && remote.actionSpectator(entities.clientPlayerModes)) {
                continue;
            }
            SimpleCollisionBox box = entity.getPossibleMovementCollisionBoxes();
            targets.add(new WorldCollisionBox(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
        }
        return targets;
    }

    private boolean hasDolphinProximity(CultPlayer player, SimulationContext context) {
        CompensatedEntities entities =
                context.getEntities() != null ? context.getEntities() : player.compensatedEntities;
        if (entities == null || entities.entityMap.isEmpty()) {
            return false;
        }
        Optional<Vec3d> trustedPosition = BedrockProfileState.trustedFeetPosition(context);
        if (trustedPosition.isEmpty()) {
            return false;
        }
        // Scan before the requested pose size is applied.
        BedrockMovementState previous = BedrockProfileState.previousState(context);
        PlayerDimensionsState dimensions =
                previous == null ? PlayerDimensionsState.DEFAULT : previous.playerDimensions();
        for (PacketEntity entity : entities.entityMap.values()) {
            if (entity == null
                    || entity.isDead
                    || entity.getType() != EntityTypeIds.DOLPHIN
                    || entities.isUntrackedRider(entity)) {
                continue;
            }
            if (isWithinDolphinProximity(
                    trustedPosition.get(),
                    dimensions,
                    entity.getPossibleMovementCollisionBoxes(),
                    context.getBedrockInput() == null
                            ? BedrockCoordinateFrame.IDENTITY
                            : context.getBedrockInput().getCoordinateFrame())) {
                return true;
            }
        }
        return false;
    }

    static boolean isWithinDolphinProximity(
            Vec3d position, PlayerDimensionsState dimensions, SimpleCollisionBox dolphinBox) {
        return isWithinDolphinProximity(position, dimensions, dolphinBox, BedrockCoordinateFrame.IDENTITY);
    }

    static boolean isWithinDolphinProximity(
            Vec3d position,
            PlayerDimensionsState dimensions,
            SimpleCollisionBox dolphinBox,
            BedrockCoordinateFrame frame) {
        if (dolphinBox == null) {
            return false;
        }
        // Expand the player's AABB by five blocks on every axis.
        double radius = dimensions.radius();
        double minX = frame.roundX(position.x() - radius) - DOLPHIN_PROXIMITY_RANGE;
        double minY = (float) position.y() - DOLPHIN_PROXIMITY_RANGE;
        double minZ = frame.roundZ(position.z() - radius) - DOLPHIN_PROXIMITY_RANGE;
        double maxX = frame.roundX(position.x() + radius) + DOLPHIN_PROXIMITY_RANGE;
        double maxY = (float) (position.y() + dimensions.height()) + DOLPHIN_PROXIMITY_RANGE;
        double maxZ = frame.roundZ(position.z() + radius) + DOLPHIN_PROXIMITY_RANGE;
        // Require strict overlap with the expanded box.
        return dolphinBox.maxX > minX
                && dolphinBox.minX < maxX
                && dolphinBox.maxY > minY
                && dolphinBox.minY < maxY
                && dolphinBox.maxZ > minZ
                && dolphinBox.minZ < maxZ;
    }
}
