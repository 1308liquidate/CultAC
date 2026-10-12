package ac.cult.cultac.checks.impl.verbose;

import ac.cult.blocksim.data.BlockDefinition;
import ac.cult.blocksim.data.BlockIds;
import ac.cult.blocksim.data.DataTables;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.protocol.ProtocolVersion;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.protocol.value.Hand;
import ac.cult.cultac.protocol.value.PlayerAction;
import ac.cult.cultac.protocol.value.PlayerCommandAction;
import ac.grim.grimac.api.storage.verbose.VerboseTags;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Cult's verbose template tags plus the write-side value encoders that pair
 * with them. Registered once; checks declare templates referencing these tag
 * names and write values through the matching {@code VerboseCodecs.x(...)}
 * encoder.
 */
public final class VerboseCodecs {
    /** {@code {packet}} sentinel for "no packet". */
    public static final int PACKET_NONE = Integer.MIN_VALUE;
    /** {@code {packet}} sentinel for transaction/pong. */
    public static final int PACKET_TRANSACTION = Integer.MIN_VALUE + 1;

    static {
        ac.cult.cultac.manager.datastore.SharedVerboseTags.registerEnum("face", Direction.values(), false);
        var version = ProtocolVersion.V26_3;
        var digging = java.util.Arrays.stream(PlayerAction.values())
                .filter(action ->
                        (action != PlayerAction.CHANGE_DESTROY_DIRECTION || version.atLeast(ProtocolVersion.V26_3))
                                && (action != PlayerAction.STAB || version.atLeast(ProtocolVersion.V1_21_11)))
                .toArray(PlayerAction[]::new);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.registerEnum("digging", digging, false);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.registerEnum("digging_lower", digging, true);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.registerEnum(
                "clicktype", ac.cult.cultac.utils.inventory.inventory.WindowClickType.values(), false);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.registerEnum(
                "clicktype_lower", ac.cult.cultac.utils.inventory.inventory.WindowClickType.values(), true);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.registerEnum(
                "entityaction", PlayerCommandAction.values(), false);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.registerEnum("hand", Hand.values(), false);
        var blocks = DataTables.defaults().registry().blocks();
        ac.cult.cultac.manager.datastore.SharedVerboseTags.register(
                "block", blocks.stream().map(BlockDefinition::key).toList(), false, "unknown", BlockIds.AIR.key());
        var items = ac.cult.cultac.protocol.data.ModelRegistryData.load(ProtocolVersion.V26_3)
                .registry("minecraft:item");
        ac.cult.cultac.manager.datastore.SharedVerboseTags.register(
                "item",
                java.util.stream.IntStream.range(0, items.size())
                        .mapToObj(items::name)
                        .toList(),
                false,
                "",
                null);
        var entities = ac.cult.blocksim.entity.EntityTypes.defaults();
        ac.cult.cultac.manager.datastore.SharedVerboseTags.register(
                "entity",
                java.util.stream.IntStream.range(0, entities.types().size())
                        .mapToObj(i -> entities.byId(i).key())
                        .toList(),
                true,
                "unknown",
                "unknown");
        ac.cult.cultac.manager.datastore.SharedVerboseTags.register(
                "packet", ac.cult.cultac.manager.datastore.SharedVerboseTags.Primitive.PACKET);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.register(
                "offset", ac.cult.cultac.manager.datastore.SharedVerboseTags.Primitive.OFFSET);
        ac.cult.cultac.manager.datastore.SharedVerboseTags.register(
                "stdnum", ac.cult.cultac.manager.datastore.SharedVerboseTags.Primitive.NUMBER);
    }

    private VerboseCodecs() {}

    /**
     * Force tag registration. Call before templates parse and before history
     * rows render; class initialization performs the actual work once.
     */
    public static void ensureRegistered() {
        // Static initializer has run by the time this returns.
    }

    /** Null-safe enum encoding for the {@code registerEnum} family of tags. */
    public static int enumId(@Nullable Enum<?> value) {
        return VerboseTags.enumId(value);
    }

    /** Keep stored action IDs aligned with the server's existing verbose decoder. */
    public static int digging(@Nullable PlayerAction action) {
        return action == null ? 0 : action.wireId(ProtocolVersion.V26_3) + 1;
    }

    /**
     * Encoder for {@code {block}}: the protocol-defined per-version block id,
     * so any product following the MC protocol decodes the same name.
     * {@code -1} when the block has no id in the player's version
     * (server-only states render as {@code unknown}).
     */
    public static int block(@NotNull BlockDefinition type, @NotNull ClientVersion version) {
        return DataTables.defaults().registry().blockIndex(type.defaultState());
    }

    /** Encoder for {@code {item}}: the server's built-in registry item id. */
    public static int item(@NotNull ac.cult.blocksim.data.ItemDefinition type, @NotNull ClientVersion version) {
        return type.id();
    }

    /** Encoder for {@code {entity}}: the shared model entity-type id. */
    public static int entity(int type, @NotNull ClientVersion version) {
        return type;
    }
}
