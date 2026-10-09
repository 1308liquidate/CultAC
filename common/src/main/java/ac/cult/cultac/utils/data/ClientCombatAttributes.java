package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.AttributeModifier;
import ac.cult.cultac.protocol.value.AttributeSnapshot;
import ac.cult.cultac.utils.latency.CompensatedEntities;
import java.util.LinkedHashMap;
import java.util.Set;

/**
 * The Java client's attack_speed, attack_knockback and attack_damage attributes. ClientPacketListener#handleUpdateAttributes
 * replaces an attribute's base and modifiers. LivingEntity#tick calls detectEquipmentUpdates only on the server:
 * weapon and effect modifiers reach the client through attribute packets, not local held-item changes. Vanilla
 * syncs attack_speed; attack_knockback and attack_damage retain their client defaults unless explicitly sent.
 */
public final class ClientCombatAttributes {
    private static final String ATTACK_SPEED = "minecraft:attack_speed";
    private static final String ATTACK_KNOCKBACK = "minecraft:attack_knockback";
    private static final String ATTACK_DAMAGE = "minecraft:attack_damage";

    private final Instance attackSpeed = new Instance(4.0, 1024.0);
    private final Instance attackKnockback = new Instance(0.0, 5.0);
    // Player#createAttributes overrides the 2.0 attack_damage default with 1.0.
    private final Instance attackDamage = new Instance(1.0, 2048.0);

    /** ClientPacketListener#handleUpdateAttributes for the local player, applied by its bundled proof. */
    public void onServerSnapshot(AttributeSnapshot snapshot) {
        Instance instance = instance(snapshot.attribute());
        if (instance == null) return;
        instance.base = snapshot.base();
        instance.modifiers.clear();
        for (AttributeModifier modifier : snapshot.modifiers()) {
            instance.modifiers.put(modifier.id(), modifier);
        }
    }

    /** ClientPacketListener#handleRespawn: KEEP_ATTRIBUTE_MODIFIERS copies modifiers, otherwise only bases. */
    public void onRespawn(boolean keepAttributeModifiers) {
        if (!keepAttributeModifiers) {
            attackSpeed.modifiers.clear();
            attackKnockback.modifiers.clear();
            attackDamage.modifiers.clear();
        }
    }

    /** ClientPacketListener#handleLogin creates a fresh LocalPlayer after Minecraft#clearClientLevel. */
    public void reset() {
        attackSpeed.reset();
        attackKnockback.reset();
        attackDamage.reset();
    }

    public double attackSpeed() {
        return attackSpeed.value();
    }

    public double attackKnockback() {
        return attackKnockback.value();
    }

    public double attackDamage() {
        return attackDamage.value();
    }

    private Instance instance(String attribute) {
        return switch (attribute) {
            case ATTACK_SPEED -> attackSpeed;
            case ATTACK_KNOCKBACK -> attackKnockback;
            case ATTACK_DAMAGE -> attackDamage;
            default -> null;
        };
    }

    private static final class Instance {
        private final double defaultBase;
        private final double maxValue;
        private double base;
        private final LinkedHashMap<String, AttributeModifier> modifiers = new LinkedHashMap<>();

        private Instance(double defaultBase, double maxValue) {
            this.defaultBase = defaultBase;
            this.maxValue = maxValue;
            this.base = defaultBase;
        }

        private void reset() {
            base = defaultBase;
            modifiers.clear();
        }

        // AttributeInstance#calculateValue, then RangedAttribute#sanitizeValue
        private double value() {
            double value = CompensatedEntities.calculateAttributeValue(
                    new AttributeSnapshot("", base, java.util.List.copyOf(modifiers.values())), Set.of());
            return Double.isNaN(value) ? 0.0 : Math.min(Math.max(value, 0.0), maxValue);
        }
    }
}
