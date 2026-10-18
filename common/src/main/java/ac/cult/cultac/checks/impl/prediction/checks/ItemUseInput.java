package ac.cult.cultac.checks.impl.prediction.checks;

import ac.cult.cultac.checks.impl.prediction.ItemUseBurstResult;
import ac.cult.cultac.checks.impl.prediction.PredictionResult;
import ac.cult.cultac.network.protocol.ClientVersion;
import ac.cult.cultac.utils.math.Vec3;

/** LivingEntity.moveRelative's unit-circle normalization and LocalPlayer's scaled square input. */
public final class ItemUseInput {
    private ItemUseInput() {}

    public static ItemUseBurstResult residual(PredictionResult result) {
        var context = result.getSimulationContext();
        Vec3 residual =
                result.getTarget().subtract(result.getAcceptedClosestToTarget()).multiply(1, 0, 1);
        Vec3 input = HorizontalAnalyzer.getBestTheoreticalPlayerInput(residual, context.getXRot());
        return new ItemUseBurstResult(
                input.x,
                input.z,
                context.getMaxSprintInputSpeed(result.getPlayer()),
                result.getSneakingInputMultiplier(),
                context.getVersion());
    }

    public static double offset(ItemUseBurstResult input, float multiplier) {
        return offset(input.x(), input.z(), input.speed(), multiplier, input.sneak(), input.version());
    }

    public static double offset(double x, double z, double speed, float multiplier) {
        return offset(x, z, speed, multiplier, 1F, ClientVersion.V_1_21_5);
    }

    static double offset(double x, double z, double speed, float multiplier, float sneak, ClientVersion version) {
        // Vanilla performs the scale in float before moveRelative multiplies by speed.
        // Both legacy unnormalized keyboard diagonals and modern square reshaping
        // have this axis bound, with the final vector limited to the unit circle.
        float inputScale = version.isOlderThan(ClientVersion.V_1_21_5)
                ? (sneak * multiplier) * 0.98F
                : (0.98F * multiplier) * sneak;
        double axis = inputScale * speed;
        double clampedX = Math.max(-axis, Math.min(axis, x));
        double clampedZ = Math.max(-axis, Math.min(axis, z));
        if (Math.hypot(clampedX, clampedZ) <= speed) return Math.hypot(x - clampedX, z - clampedZ);
        double length = Math.hypot(x, z);
        double circleX = x * speed / length;
        double circleZ = z * speed / length;
        if (Math.abs(circleX) <= axis && Math.abs(circleZ) <= axis) return length - speed;
        // The nearest point is one of the square/circle intersections, not a
        // radial rescaling of the square projection (which can overstate error).
        double other = Math.sqrt(Math.max(0, speed * speed - axis * axis));
        return Math.min(
                Math.hypot(Math.abs(x) - axis, Math.abs(z) - other),
                Math.hypot(Math.abs(x) - other, Math.abs(z) - axis));
    }
}
