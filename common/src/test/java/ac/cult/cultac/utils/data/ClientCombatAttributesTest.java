package ac.cult.cultac.utils.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClientCombatAttributesTest {
    @Test
    void serverSnapshotsReplaceWeaponAndEffectModifiersTogether() {
        ClientCombatAttributes attributes = new ClientCombatAttributes();
        attributes.onServerSnapshot(new AttributeSnapshot(
                "minecraft:attack_speed", 4.0,
                List.of(modifier("minecraft:base_attack_speed", -2.4000000953674316,
                        AttributeModifier.Operation.ADD_VALUE))));
        assertEquals(1.5999999046325684, attributes.attackSpeed(), 1e-9);

        attributes.onServerSnapshot(new AttributeSnapshot(
                "minecraft:attack_speed", 4.0,
                List.of(modifier("minecraft:base_attack_speed", -3.0, AttributeModifier.Operation.ADD_VALUE),
                        modifier("minecraft:effect.haste", 0.1, AttributeModifier.Operation.ADD_MULTIPLIED_BASE))));
        assertEquals(1.1, attributes.attackSpeed(), 1e-9);
        assertEquals((float) (20.0 / 1.1), ClientAttackStrength.delay(attributes.attackSpeed()));

        attributes.onServerSnapshot(new AttributeSnapshot("minecraft:attack_speed", 6.0, List.of()));
        assertEquals(6.0, attributes.attackSpeed(), 1e-9);
    }

    @Test
    void unsyncedCombatAttributesUseClientDefaultsButAcceptExplicitPackets() {
        ClientCombatAttributes attributes = new ClientCombatAttributes();
        assertEquals(1.0, attributes.attackDamage(), 1e-9);
        assertEquals(0.0, attributes.attackKnockback(), 1e-9);
        attributes.onServerSnapshot(new AttributeSnapshot("minecraft:attack_damage", 12.0, List.of()));
        attributes.onServerSnapshot(new AttributeSnapshot("minecraft:attack_knockback", 0.75, List.of()));
        assertEquals(12.0, attributes.attackDamage(), 1e-9);
        assertEquals(0.75, attributes.attackKnockback(), 1e-9);
    }

    @Test
    void respawnKeepsBasesAndOnlyKeepsModifiersWhenRequested() {
        ClientCombatAttributes attributes = new ClientCombatAttributes();
        attributes.onServerSnapshot(new AttributeSnapshot(
                "minecraft:attack_speed", 6.0,
                List.of(modifier("plugin:weapon_speed", -2.0, AttributeModifier.Operation.ADD_VALUE))));
        attributes.onRespawn(true);
        assertEquals(4.0, attributes.attackSpeed(), 1e-9);
        attributes.onRespawn(false);
        assertEquals(6.0, attributes.attackSpeed(), 1e-9);
        attributes.reset();
        assertEquals(4.0, attributes.attackSpeed(), 1e-9);
        assertEquals(1.0, attributes.attackDamage(), 1e-9);
        assertEquals(0.0, attributes.attackKnockback(), 1e-9);
    }

    private static AttributeModifier modifier(String id, double amount, AttributeModifier.Operation operation) {
        return new AttributeModifier(id, amount, operation);
    }
}
