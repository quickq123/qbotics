package qbotics.network;

import net.minecraft.core.Direction;

/**
 * Which face of its target a bus or output reaches through (design doc §11.0), described
 * from where the bus stands looking at the target. A furnace takes ingredients through its
 * top and fuel through its sides, so the face matters.
 */
public enum AccessFace {
    /** The face touching the bus. */
    NEAR,
    /** The face opposite the bus. */
    FAR,
    TOP,
    BOTTOM,
    LEFT,
    RIGHT;

    public String word() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The face for a word such as "top", or null if there is no such face. */
    public static AccessFace parse(String word) {
        for (AccessFace face : values()) {
            if (face.word().equals(word)) {
                return face;
            }
        }
        return null;
    }

    /**
     * The real side of the target block.
     *
     * @param pointing the direction from the bus to its target. When the bus is above or
     *                 below the target there is no natural "top", so the faces are named as
     *                 on a map: top is north, left is west.
     */
    public Direction resolve(Direction pointing) {
        boolean vertical = pointing.getAxis() == Direction.Axis.Y;
        return switch (this) {
            case NEAR -> pointing.getOpposite();
            case FAR -> pointing;
            case TOP -> vertical ? Direction.NORTH : Direction.UP;
            case BOTTOM -> vertical ? Direction.SOUTH : Direction.DOWN;
            case LEFT -> vertical ? Direction.WEST : pointing.getCounterClockWise();
            case RIGHT -> vertical ? Direction.EAST : pointing.getClockWise();
        };
    }
}
