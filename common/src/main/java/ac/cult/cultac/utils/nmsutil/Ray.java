package ac.cult.cultac.utils.nmsutil;

import ac.cult.cultac.player.CultPlayer;
import ac.cult.cultac.utils.math.Vector3dm;

// Copied directly from Hawk
public class Ray {

    private Vector3dm origin;
    private Vector3dm direction;

    public Ray(CultPlayer player, double x, double y, double z, float xRot, float yRot) {
        this.origin = new Vector3dm(x, y, z);
        this.direction = calculateDirection(player, xRot, yRot);
    }

    // Account for FastMath by using player's trig handler
    // Copied from hawk which probably copied it from NMS
    public static Vector3dm calculateDirection(CultPlayer player, float xRot, float yRot) {
        Vector3dm vector = new Vector3dm();
        float rotX = (float) Math.toRadians(xRot);
        float rotY = (float) Math.toRadians(yRot);
        vector.setY(-player.trigHandler.sin(rotY));
        double xz = player.trigHandler.cos(rotY);
        vector.setX(-xz * player.trigHandler.sin(rotX));
        vector.setZ(xz * player.trigHandler.cos(rotX));
        return vector;
    }

    public Vector3dm getPointAtDistance(double distance) {
        Vector3dm dir = new Vector3dm(direction.getX(), direction.getY(), direction.getZ());
        Vector3dm orig = new Vector3dm(origin.getX(), origin.getY(), origin.getZ());
        return orig.add(dir.multiply(distance));
    }

    public Vector3dm getOrigin() {
        return origin;
    }
}
