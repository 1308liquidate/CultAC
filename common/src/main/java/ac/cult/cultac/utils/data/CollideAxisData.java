package ac.cult.cultac.utils.data;

import ac.cult.cultac.utils.collisions.datatypes.SimpleCollisionBox;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

@NoArgsConstructor
@AllArgsConstructor
@Data
@ToString
public class CollideAxisData {
    CollideResult x;
    CollideResult yPos;
    CollideResult yNeg;
    CollideResult z;
    List<SimpleCollisionBox> unknown = new ArrayList<>();

    public void setX(CollideResult newX) {
        x = x == null ? newX : x.combine(newX);
    }

    public void setYPos(CollideResult newYPos) {
        yPos = yPos == null ? newYPos : yPos.combine(newYPos);
    }

    public void setYNeg(CollideResult newYNeg) {
        yNeg = yNeg == null ? newYNeg : yNeg.combine(newYNeg);
    }

    public void setZ(CollideResult newZ) {
        z = z == null ? newZ : z.combine(newZ);
    }

    public boolean couldCollideHorizontally() {
        return x.isLikelyCollide() || z.isLikelyCollide();
    }

    @AllArgsConstructor
    @Data
    public static class CollideResult {
        boolean isLikelyCollide;
        double result;
        // Direction (+1/-1) the horizontal result was probed in, since a flush result of 0 has no sign; 0 if unknown.
        int side;

        public CollideResult(boolean isLikelyCollide, double result) {
            this(isLikelyCollide, result, 0);
        }

        @Override
        public String toString() {
            return isLikelyCollide + " " + result;
        }

        public CollideResult combine(CollideResult newResult) {
            isLikelyCollide = isLikelyCollide || newResult.isLikelyCollide;
            boolean areWeGreater = Math.abs(result) > Math.abs(newResult.result);
            if (areWeGreater) {
                side = newResult.side;
            } else if (Math.abs(result) == Math.abs(newResult.result) && side != newResult.side) {
                side = 0;
            }
            result = areWeGreater ? newResult.result : result;
            return this;
        }

        public double inMovementSpace(double stuckSpeedMultiplier) {
            return result * stuckSpeedMultiplier;
        }
    }
}
