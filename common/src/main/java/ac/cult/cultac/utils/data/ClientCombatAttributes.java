package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.AttributeSnapshot;

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

    private final ClientAttributeInstance attackSpeed = new ClientAttributeInstance(4.0, 0.0, 1024.0);
    private final ClientAttributeInstance attackKnockback = new ClientAttributeInstance(0.0, 0.0, 5.0);
    // Player#createAttributes overrides the 2.0 attack_damage default with 1.0.
    private final ClientAttributeInstance attackDamage = new ClientAttributeInstance(1.0, 0.0, 2048.0);

    /** ClientPacketListener#handleUpdateAttributes for the local player, applied by its bundled proof. */
    public void onServerSnapshot(AttributeSnapshot snapshot) {
        ClientAttributeInstance instance = instance(snapshot.attribute());
        if (instance != null) instance.onServerSnapshot(snapshot);
    }

    /** ClientPacketListener#handleRespawn: KEEP_ATTRIBUTE_MODIFIERS copies modifiers, otherwise only bases. */
    public void onRespawn(boolean keepAttributeModifiers) {
        if (!keepAttributeModifiers) {
            attackSpeed.clearModifiers();
            attackKnockback.clearModifiers();
            attackDamage.clearModifiers();
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

    private ClientAttributeInstance instance(String attribute) {
        return switch (attribute) {
            case ATTACK_SPEED -> attackSpeed;
            case ATTACK_KNOCKBACK -> attackKnockback;
            case ATTACK_DAMAGE -> attackDamage;
            default -> null;
        };
    }
}
