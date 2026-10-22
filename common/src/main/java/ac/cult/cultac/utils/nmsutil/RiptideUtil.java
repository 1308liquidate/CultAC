package ac.cult.cultac.utils.nmsutil;

import ac.cult.blocksim.engine.SimItemStack;
import ac.cult.cultac.checks.impl.prediction.DesyncStatus;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.checks.impl.prediction.pipeline.java.JavaFallDistance;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;

public final class RiptideUtil {
    private static final int MIN_CHARGE_TICKS = 10;

    private RiptideUtil() {}

    public static int getRiptideLevel(SimItemStack item) {
        if (item == null || item.getItem() != ac.cult.cultac.utils.inventory.ItemTypes.TRIDENT) {
            return 0;
        }
        return ac.cult.cultac.utils.inventory.ItemUtil.enchantmentLevel(item, "minecraft:riptide");
    }

    public static boolean isValidRiptideRelease(CultPlayer player, Hand hand) {
        return isFullyCharged(player, hand) && canUseRiptideAtCurrentState(player);
    }

    public static String invalidReleaseReason(CultPlayer player, Hand hand) {
        if (!isFullyCharged(player, hand)) {
            return "charge=" + getTrackedUseTicks(player, hand);
        }
        if (player.compensatedEntities.getSelf().inVehicle()) {
            return "passenger";
        }
        return "dry";
    }

    public static boolean isFullyCharged(CultPlayer player, Hand hand) {
        return hasFullCharge(getTrackedUseTicks(player, hand));
    }

    public static boolean hasFullCharge(int useTicks) {
        return useTicks >= MIN_CHARGE_TICKS;
    }

    public static int getTrackedUseTicks(CultPlayer player, Hand hand) {
        if (hand == null || hand != player.packetStateData.riptideUseHand) {
            return 0;
        }
        return Math.max(
                0, player.packetStateData.acceptedClientTick - player.packetStateData.riptideUseStartClientTick);
    }

    public static boolean canUseRiptideAtCurrentState(CultPlayer player) {
        if (player.compensatedEntities.getSelf().inVehicle()) {
            return false;
        }
        return inWaterOrRain(player).determineOptimistically();
    }

    /** Player#isInWaterOrRain as TridentItem#use and TridentItem#releaseUsing read it from Minecraft#handleKeybinds. */
    public static DesyncStatus inWaterOrRain(CultPlayer player) {
        return isInRain(player) ? DesyncStatus.TRUE : wasTouchingWater(player);
    }

    /** Entity#isInRain: Level#isRainingAt at Entity#blockPosition or at its column's bounding-box top. */
    private static boolean isInRain(CultPlayer player) {
        BlockPos pos = new BlockPos(
                (int) Math.floor(player.x), (int) Math.floor(player.y), (int) Math.floor(player.z));
        return player.compensatedWorld.clientIsRainingAt(pos)
                || player.compensatedWorld.clientIsRainingAt(
                        new BlockPos(pos.getX(), (int) Math.floor(player.boundingBox.maxY), pos.getZ()));
    }

    /**
     * Whether the last move left Entity#wasTouchingWater set, so TridentItem#releaseUsing spins this valid release.
     */
    public static boolean isCertainRiptideRelease(CultPlayer player) {
        if (player.compensatedEntities.getSelf().inVehicle()) {
            return false;
        }
        PredictionResult last = player.checkManager.getSimulationProcessor().getLastPrediction();
        JavaFallDistance.Moved moved = last == null ? null : last.getJavaCommittedMove();
        return moved != null && moved.touchingWater() == DesyncStatus.TRUE;
    }

    /**
     * Entity#wasTouchingWater as TridentItem#releaseUsing reads it: Minecraft#handleKeybinds runs before the tick's
     * baseTick, so the flag is the one the last move left after LivingEntity#checkFallDamage refreshed fluids.
     */
    private static DesyncStatus wasTouchingWater(CultPlayer player) {
        PredictionResult last = player.checkManager.getSimulationProcessor().getLastPrediction();
        JavaFallDistance.Moved moved = last == null ? null : last.getJavaCommittedMove();
        return moved != null ? moved.touchingWater() : DesyncStatus.fromBoolean(isTouchingWater(player));
    }

    public static boolean isTouchingWater(CultPlayer player) {
        SimpleCollisionBox box = GetBoundingBox.getPlayerBoundingBox(player, player.x, player.y, player.z)
                .copy();
        box.expand(-1.0E-7D);
        return Collisions.hasState(player, box, (state, pos) -> isWater(state));
    }

    private static boolean isWater(int state) {
        var fluid = ClientFluidQueries.modelFluid(state);
        return fluid.is("minecraft:water") || fluid.is("minecraft:flowing_water");
    }

    public static void clearTrackedUse(CultPlayer player) {
        player.packetStateData.riptideUseHand = null;
        player.packetStateData.riptideUseStartClientTick = Integer.MIN_VALUE;
    }
}
