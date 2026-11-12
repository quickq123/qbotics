package qbotics.camera;

import net.minecraft.core.BlockPos;

/**
 * A picture a Qbot took (design doc §8): for each pixel, what its ray hit, not a colour. Each
 * player's game colours it from the real block textures (see qbotics.client.display.PictureColors).
 *
 * <p>{@code data} holds {@link #BYTES_PER_PIXEL} bytes a pixel, row by row from the top left:
 * kind (0 sky, 1 block, 2 creature), the block state's id or the creature's kind (4 bytes, big
 * end first), the face hit (a Direction's number), where on the face (u in the high four bits,
 * v in the low, in 16ths), the light there (0-15) and the distance (eighths of a block).
 *
 * @param origin where it was taken, for the colours that depend on the place (grass, water)
 */
public record Picture(int width, int height, BlockPos origin, byte[] data) {
    public static final int BYTES_PER_PIXEL = 9;
    public static final int SKY = 0;
    public static final int BLOCK = 1;
    public static final int CREATURE = 2;
    /** Creature kinds, sent in place of a block id: how a creature is coloured. */
    public static final int MONSTER = 0;
    public static final int ANIMAL = 1;
    public static final int PLAYER = 2;
    public static final int OTHER = 3;
}
