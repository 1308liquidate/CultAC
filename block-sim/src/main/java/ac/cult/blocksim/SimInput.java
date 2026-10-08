package ac.cult.blocksim;

import ac.cult.blocksim.engine.*;
import ac.cult.blocksim.interaction.BreakSession;
import ac.cult.blocksim.interaction.MiningSpeed;
import java.util.IdentityHashMap;

/** Compensated inputs. Simulation forks mutable stacks and preserves their aliases. */
public record SimInput(SimWorldView world, SimPlayer player, SimCooldowns cooldowns,
                       MiningSpeed.State mining, BreakSession.State breaking, ac.cult.blocksim.data.HolderSets.Overlay tags,
                       BlockSimClientVersion clientVersion) {
    public SimInput {
        java.util.Objects.requireNonNull(tags);
        java.util.Objects.requireNonNull(clientVersion);
    }
    public SimInput(SimWorldView world, SimPlayer player, SimCooldowns cooldowns,
                    MiningSpeed.State mining, BreakSession.State breaking, ac.cult.blocksim.data.HolderSets.Overlay tags) {
        this(world, player, cooldowns, mining, breaking, tags, BlockSimClientVersion.V26_3);
    }
    public SimInput(SimWorldView world, SimPlayer player, SimCooldowns cooldowns,
                    MiningSpeed.State mining, BreakSession.State breaking) {
        this(world, player, cooldowns, mining, breaking, ac.cult.blocksim.data.HolderSets.Overlay.EMPTY);
    }
    SimInput fork() {
        var copies = new IdentityHashMap<SimItemStack, SimItemStack>();
        java.util.function.UnaryOperator<SimItemStack> copy = stack -> copies.computeIfAbsent(stack, SimItemStack::copy);
        var result = player.copy(copy);
        var session = new BreakSession.State(breaking.destroying(), breaking.pos(), breaking.direction(), copy.apply(breaking.item()),
            breaking.progress(), breaking.ticks(), breaking.delay());
        return new SimInput(world, result, new SimCooldowns(cooldowns.tickCount(), cooldowns.snapshot()), mining, session, tags, clientVersion);
    }
}
