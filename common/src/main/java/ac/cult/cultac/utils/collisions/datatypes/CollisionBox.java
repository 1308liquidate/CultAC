package ac.cult.cultac.utils.collisions.datatypes;

import java.util.List;

public interface CollisionBox {
    boolean isCollided(SimpleCollisionBox other);

    boolean isIntersected(SimpleCollisionBox other);

    CollisionBox copy();

    CollisionBox offset(double x, double y, double z);

    void downCast(List<SimpleCollisionBox> list);

    /** Movement may need the original voxel grid even when geometry uses merged boxes. */
    default void downCastForMovement(List<SimpleCollisionBox> list) { downCast(list); }

    boolean isNull();

    boolean isFullBlock();
}
