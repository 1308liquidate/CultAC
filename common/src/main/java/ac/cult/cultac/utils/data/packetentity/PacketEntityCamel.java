package ac.cult.cultac.utils.data.packetentity;

import ac.cult.cultac.player.CultPlayer;

public class PacketEntityCamel extends PacketEntityHorse {

    public boolean dashing = false;
    public int dashCooldown;
    public boolean lastPredictedInLiquid;
    // Camel LAST_POSE_CHANGE_TICK: negative while sitting; its magnitude is the game time of the last pose change.
    public long lastPoseChangeTick;

    public PacketEntityCamel(CultPlayer player, int entityId, int type, double x, double y, double z, float xRot) {
        super(player, entityId, type, x, y, z, xRot);
        applyCamelAttributeDefaults();
    }

    private void applyCamelAttributeDefaults() {
        // Vanilla camel attribute defaults.
        this.jumpStrength = 0.42F;
        this.movementSpeedAttribute = 0.09f;
        this.stepHeightAttribute = 1.5D;
    }

    /** Camel#isTamed, which CamelHusk inherits: a camel never carries FLAG_TAME and is always tamed. */
    @Override
    public boolean isTamed() {
        return true;
    }

    public void tickDashCooldown() {
        if (dashing && dashCooldown < 50 && (onGround || lastPredictedInLiquid || getRiding() != null)) {
            dashing = false;
        }
        if (dashCooldown > 0) {
            dashCooldown--;
        }
    }

    public boolean isCamelSitting() {
        return lastPoseChangeTick < 0L;
    }

    /** Camel#getPoseTime at the client game time {@code gameTime}. */
    public long poseTime(long gameTime) {
        return gameTime - Math.abs(lastPoseChangeTick);
    }

    public void setDashingFromMetadata(boolean dashing) {
        if (this.dashing != dashing && dashCooldown == 0) {
            dashCooldown = 55;
        }
        this.dashing = dashing;
    }
}
