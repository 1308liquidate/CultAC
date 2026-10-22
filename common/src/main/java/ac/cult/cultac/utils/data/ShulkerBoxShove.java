package ac.cult.cultac.utils.data;

import ac.cult.cultac.protocol.value.BlockPos;
import ac.cult.cultac.protocol.value.Direction;
import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.cult.cultac.utils.math.Vec3;

/** One opened shulker box lid's ShulkerBoxBlockEntity#moveCollidedEntities: its entity query area and shove. */
public record ShulkerBoxShove(SimpleCollisionBox area, Vec3 movement) {
    public static ShulkerBoxShove of(BlockPos pos, Direction facing, float progressOld, float progress) {
        // Shulker#getProgressDeltaAabb(1.0F, facing, progressOld, progress, pos.getBottomCenter()). The block entity
        // passes its full progress here, while getBoundingBox only raises the lid by 0.5F * progress.
        double max = Math.max(progressOld, progress);
        double min = Math.min(progressOld, progress);
        int stepX = facing.getStepX(), stepY = facing.getStepY(), stepZ = facing.getStepZ();
        double minX = -0.5, minY = 0.0, minZ = -0.5;
        double maxX = 0.5, maxY = 1.0, maxZ = 0.5;
        // AABB#expandTowards(step * max), then AABB#contract(-step * (1 + min)).
        if (stepX < 0) minX += stepX * max;
        else if (stepX > 0) maxX += stepX * max;
        if (stepY < 0) minY += stepY * max;
        else if (stepY > 0) maxY += stepY * max;
        if (stepZ < 0) minZ += stepZ * max;
        else if (stepZ > 0) maxZ += stepZ * max;
        double contractX = -stepX * (1.0 + min), contractY = -stepY * (1.0 + min), contractZ = -stepZ * (1.0 + min);
        if (contractX < 0) minX -= contractX;
        else if (contractX > 0) maxX -= contractX;
        if (contractY < 0) minY -= contractY;
        else if (contractY > 0) maxY -= contractY;
        if (contractZ < 0) minZ -= contractZ;
        else if (contractZ > 0) maxZ -= contractZ;
        double x = pos.getX() + 0.5, y = pos.getY(), z = pos.getZ() + 0.5;
        SimpleCollisionBox area = new SimpleCollisionBox(minX + x, minY + y, minZ + z, maxX + x, maxY + y, maxZ + z);
        // Entity#move(MoverType.SHULKER_BOX, (size + 0.01) * step) on the moved area's sizes.
        Vec3 movement = new Vec3(
                (area.maxX - area.minX + 0.01) * stepX,
                (area.maxY - area.minY + 0.01) * stepY,
                (area.maxZ - area.minZ + 0.01) * stepZ);
        return new ShulkerBoxShove(area, movement);
    }

    /** Level#getEntities: AABB#intersects is strict on every axis. */
    public boolean reaches(SimpleCollisionBox entityBox) {
        return entityBox.minX < area.maxX
                && entityBox.maxX > area.minX
                && entityBox.minY < area.maxY
                && entityBox.maxY > area.minY
                && entityBox.minZ < area.maxZ
                && entityBox.maxZ > area.minZ;
    }
}
