package ac.cult.cultac.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ac.cult.cultac.manager.player.SetbackTeleportUtil;
import ac.cult.cultac.protocol.value.MovementEffect;
import ac.cult.cultac.utils.data.SetBackData;
import ac.cult.cultac.utils.math.Vec3;
import org.junit.jupiter.api.Test;

/** A setback sends the position and velocity the 26.3 client reaches by one input-less tick from the safe state. */
class SetbackVelocityTest {
    private static final double GRAVITY = 0.08D;
    // LivingEntity#travelInAir: computeModifiedFriction(0.98F, 1.0) for the vertical drag and Entity#getAirDrag.
    private static final double VERTICAL_DRAG = 0.98F;

    @Test
    void aLevitatingSetbackRises() throws Exception {
        try (var f = new JavaTickFixture()) {
            f.player.compensatedEntities.getSelf().addPotionEffect(MovementEffect.LEVITATION, 0);
            Vec3 start = new Vec3(.5, JavaTickFixture.FLOOR_Y + 6, .5);
            f.place(start, new Vec3(0, .1, 0));
            f.tick(start.add(0, .1, 0), false);
            SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
            double carried = levitated(.1, 0);
            assertEquals(carried, teleports.lastKnownGoodPosition.getVector().y, 1.0E-12);

            SetBackData setback = violationSetback(f);

            assertEquals(start.y + .1 + carried, setback.getTeleportData().getLocation().y, 1.0E-12);
            assertEquals(levitated(carried, 0), setback.getVelocity().y, 1.0E-12);
        }
    }

    private static SetBackData violationSetback(JavaTickFixture f) {
        // The client has the chunk it stands in once it answers a ping sent after it.
        f.pongThrough(f.player.sendTrailingTransaction());
        SetbackTeleportUtil teleports = f.player.getSetbackTeleportUtil();
        assertTrue(teleports.executeViolationSetback());
        return teleports.getRequiredSetBack();
    }

    /** LivingEntity#travelInAir: levitation replaces gravity before the vertical drag. */
    private static double levitated(double y, int amplifier) {
        return (y + (0.05D * (amplifier + 1) - y) * 0.2D) * VERTICAL_DRAG;
    }
}
