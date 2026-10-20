package ac.cult.cultac.checks.impl.prediction;

import ac.cult.cultac.checks.impl.prediction.checks.HorizontalAnalyzer;
import ac.cult.cultac.checks.impl.prediction.stage.uncertainty.MovementTrace;
import ac.cult.cultac.utils.data.CollideAxisData;
import ac.cult.cultac.utils.math.Vec3;

/**
 * The client's {@code horizontalCollision && !minorHorizontalCollision} after an accepted move, which
 * LocalPlayer#shouldStopRunSprinting reads in the next aiStep.
 *
 * <p>Entity#move sets horizontalCollision when the collided movement differs from the attempted delta by
 * {@code Mth.equal}'s 1e-5 on X or Z, and minorHorizontalCollision from LocalPlayer#isHorizontalCollisionMinor on the
 * collided movement: the angle between it and the xxa/zza input, rotated by yaw, is below 8 degrees. No input, or a
 * collided movement under 1e-5 squared, is never minor. The collided movement is the reported delta, so only the
 * attempted delta is unknown. KeyboardInput's move vector only points in the eight key directions, which
 * LocalPlayer#modifyInput scales without turning, so each direction is tested with any input length up to the
 * accepted input allowance.
 */
public final class ClientHorizontalCollision {
    private static final double TOLERANCE = HorizontalAnalyzer.HORIZONTAL_INPUT_UNCERTAINTY;
    private static final double MINOR_ANGLE = 0.13962634F;

    private ClientHorizontalCollision() {}

    /** TRUE only when every key input consistent with the accepted move ends in a hard collision. */
    public static DesyncStatus hard(PredictionResult result) {
        SimulationContext context = result.getSimulationContext();
        if (context == null
                || context.getVehicle() != null
                || result.getValidMovements() == null
                || result.getCollideAxisData() == null
                || result.getInitialStartingVel().isTickSkip()) return DesyncStatus.UNKNOWN;
        MovementTrace trace = result.getValidMovements().getClosestTrace();
        if (trace == null) return DesyncStatus.UNKNOWN;

        Vec3 target = result.getTarget();
        CollideAxisData collide = result.getCollideAxisData();
        int wallX = wallSide(collide.getX(), target.x);
        int wallZ = wallSide(collide.getZ(), target.z);
        if (wallX == 0 && wallZ == 0) return DesyncStatus.FALSE;

        Vec3 movement = trace.preCollisionMovement();
        Vec3 stuck = trace.position().collisionStuckSpeedMultiplier(context);
        double stuckX = stuck == null ? 1 : stuck.x;
        double stuckZ = stuck == null ? 1 : stuck.z;
        double maxInput = result.getHorizontalInputRadius();
        double yaw = context.getXRot() * (Math.PI / 180.0);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        boolean couldBeHard = false;
        boolean couldBeSoft = false;
        for (int strafe = -1; strafe <= 1; strafe++) {
            for (int forward = -1; forward <= 1; forward++) {
                double length = Math.sqrt(strafe * strafe + forward * forward);
                double dirX = length == 0 ? 0 : (strafe * cos - forward * sin) / length;
                double dirZ = length == 0 ? 0 : (forward * cos + strafe * sin) / length;
                Axis x = new Axis(movement.x, dirX * stuckX, target.x, wallX);
                Axis z = new Axis(movement.z, dirZ * stuckZ, target.z, wallZ);

                double[] consistent = {0, length == 0 ? 0 : maxInput};
                if (!x.consistent(consistent) || !z.consistent(consistent)) continue;
                double low = consistent[0];
                double high = consistent[1];

                // The minor angle is measured on the collided movement, which the reported delta fixes.
                boolean minor = length != 0 && isMinor(dirX, dirZ, target.x, target.z);
                // Collision is certain where a wall axis overshoots by more than the tolerance, else it may not exist.
                double[] noCollision = {low, high};
                if (minor || x.reachesWithin(noCollision) && z.reachesWithin(noCollision)) couldBeSoft = true;
                if (!minor && (x.overshoots(new double[] {low, high}) || z.overshoots(new double[] {low, high}))) {
                    couldBeHard = true;
                }
            }
        }
        if (!couldBeHard && !couldBeSoft) return DesyncStatus.UNKNOWN;
        return couldBeHard ? (couldBeSoft ? DesyncStatus.UNKNOWN : DesyncStatus.TRUE) : DesyncStatus.FALSE;
    }

    /** +1/-1 when the move ends flush against a wall on that side of the axis, else 0. */
    private static int wallSide(CollideAxisData.CollideResult result, double target) {
        if (result == null || !result.isLikelyCollide() || result.getSide() == 0) return 0;
        return Math.abs(result.getResult() - target) <= TOLERANCE ? result.getSide() : 0;
    }

    private static boolean isMinor(double dirX, double dirZ, double movementX, double movementZ) {
        double movementLengthSquared = movementX * movementX + movementZ * movementZ;
        if (movementLengthSquared < 1.0E-5F) return false;
        double dot = dirX * movementX + dirZ * movementZ;
        return Math.acos(Math.min(1, dot / Math.sqrt(movementLengthSquared))) < MINOR_ANGLE;
    }

    /** One horizontal axis of the attempted movement {@code base + input * t} against the reported delta. */
    private record Axis(double base, double input, double target, int wall) {
        double at(double t) {
            return base + input * t;
        }

        /** Narrows {@code range} to the input lengths that reach the reported delta, false if none do. */
        boolean consistent(double[] range) {
            // A free axis must match the delta; a wall axis must reach it and may push further into the wall.
            double min = wall == 0 ? target - TOLERANCE : wall > 0 ? target - TOLERANCE : Double.NEGATIVE_INFINITY;
            double max = wall == 0 ? target + TOLERANCE : wall > 0 ? Double.POSITIVE_INFINITY : target + TOLERANCE;
            return clamp(range, min, max);
        }

        /** Narrows {@code range} to the input lengths that stay within the tolerance of a wall, false if none do. */
        boolean reachesWithin(double[] range) {
            return wall == 0 || clamp(range, target - TOLERANCE, target + TOLERANCE);
        }

        /** Narrows {@code range} to the input lengths that push past the wall by more than the tolerance. */
        boolean overshoots(double[] range) {
            if (wall == 0) return false;
            double min = wall > 0 ? Math.nextUp(target + TOLERANCE) : Double.NEGATIVE_INFINITY;
            double max = wall > 0 ? Double.POSITIVE_INFINITY : Math.nextDown(target - TOLERANCE);
            return clamp(range, min, max);
        }

        private boolean clamp(double[] range, double min, double max) {
            if (input == 0) return base >= min && base <= max && range[0] <= range[1];
            double first = (min - base) / input;
            double second = (max - base) / input;
            range[0] = Math.max(range[0], Math.min(first, second));
            range[1] = Math.min(range[1], Math.max(first, second));
            return range[0] <= range[1];
        }
    }
}
