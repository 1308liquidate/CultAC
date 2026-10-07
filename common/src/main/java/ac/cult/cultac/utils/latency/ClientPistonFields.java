package ac.cult.cultac.utils.latency;

import ac.cult.blocksim.data.nbt.NbtValue;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.data.ModelBlockStates;
import ac.cult.cultac.protocol.value.Direction;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** PistonMovingBlockEntity.loadAdditional, applied to an existing client entity. */
final class ClientPistonFields {
    record Fields(int movedState, Direction direction, float progress, boolean extending, boolean source) {}
    private record Versions(ProtocolVersion source, ProtocolVersion client) {}
    private record Projection(ClientBlockStateNbt nbt, ModelBlockStates states) {}
    private static final Map<Versions, Projection> PROJECTIONS = new ConcurrentHashMap<>();

    private ClientPistonFields() {}

    static Fields load(CultPlayer player, NbtValue.Compound tag) {
        var versions = new Versions(player.getObservedProtocol(),
                ProtocolVersion.of(player.getClientVersion().getProtocolVersion()));
        var projection = PROJECTIONS.computeIfAbsent(versions, v -> new Projection(
                new ClientBlockStateNbt(v.source()), ModelBlockStates.project(v.source(), v.client(), ProtocolVersion.V26_3)));
        int movedState = projection.states().toModel(projection.nbt().read(tag.values().get("blockState")));
        NbtValue facing = tag.values().get("facing");
        // 1.21.5 replaced CompoundTag.getInt with Direction.LEGACY_ID_CODEC (Codec.BYTE).
        int direction = player.getClientVersion().isOlderThan(ClientVersion.V_1_21_5)
                ? integer(facing) : facing instanceof NbtValue.Numeric n ? n.value().byteValue() : 0;
        float progress = tag.values().get("progress") instanceof NbtValue.Numeric n ? n.value().floatValue() : 0;
        return new Fields(movedState, Direction.from3DDataValue(direction), progress,
                bool(tag.values().get("extending")), bool(tag.values().get("source")));
    }

    private static int integer(NbtValue value) {
        if (!(value instanceof NbtValue.Numeric number)) return 0;
        if (number.kind() != NbtValue.Kind.FLOAT && number.kind() != NbtValue.Kind.DOUBLE)
            return number.value().intValue();
        double floating = number.value().doubleValue();
        int integer = (int) floating;
        return floating < integer ? integer - 1 : integer; // Mth.floor, including overflow behavior.
    }

    private static boolean bool(NbtValue value) {
        return (byte) integer(value) != 0; // CompoundTag.getBoolean[Or] reads NumericTag.getAsByte/byteValue.
    }
}
