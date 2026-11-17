package qbotics.camera;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * A proof of concept (2026-11-17), alongside {@link Camera}, not replacing it: instead of tracing
 * a ray for every pixel, the server lists the blocks in the Qbot's cone of view that have a face
 * it could see (a face turned towards its eye, and not covered by the block beside it), and the
 * player's game draws those faces with the game's own models and textures
 * (qbotics.client.display.ConeRenderer). What is hidden further back is sorted out by the
 * drawing's depth, so the server never traces a ray. No light: the Qbot sees as with night vision.
 */
public final class ConeView {
    /** How far ahead it sees, in blocks. */
    public static final int RANGE = 24;
    /** Across, in degrees (as the camera). */
    public static final double FOV = 70;

    private ConeView() {
    }

    /**
     * A block it sees: where (blocks from the Qbot, along the world's axes), what (a block state
     * ID), and which of its faces could be seen (a bit for each Direction's 3D data value).
     */
    public record Seen(int dx, int dy, int dz, int state, int faces) {
    }

    /** The eye: the middle of the Qbot's front face, looking the way it faces. */
    public static Vec3 eye(Direction facing) {
        return new Vec3(0.5, 0.5, 0.5).add(Vec3.atLowerCornerOf(facing.getUnitVec3i()).scale(0.51));
    }

    /** What a Qbot at {@code at}, facing {@code facing}, sees in a picture height/width {@code aspect}. */
    public static List<Seen> collect(ServerLevel level, BlockPos at, Direction facing, double aspect) {
        Vec3 forward = Vec3.atLowerCornerOf(facing.getUnitVec3i());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getUnitVec3i());
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 eye = eye(facing);
        double across = Math.tan(Math.toRadians(FOV / 2));
        double upDown = across * aspect;
        double margin = 0.87; // half a block's diagonal: a block partly in view counts
        List<Seen> seen = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
        for (int ahead = 1; ahead <= RANGE; ahead++) {
            int wide = (int) Math.ceil((ahead + 1) * across) + 1;
            int high = (int) Math.ceil((ahead + 1) * upDown) + 1;
            for (int side = -wide; side <= wide; side++) {
                for (int lift = -high; lift <= high; lift++) {
                    int dx = facing.getStepX() * ahead + facing.getClockWise().getStepX() * side;
                    int dz = facing.getStepZ() * ahead + facing.getClockWise().getStepZ() * side;
                    Vec3 centre = new Vec3(dx + 0.5, lift + 0.5, dz + 0.5).subtract(eye);
                    double z = centre.dot(forward);
                    if (z <= 0 || Math.abs(centre.dot(right)) - margin > (z + margin) * across
                            || Math.abs(centre.dot(up)) - margin > (z + margin) * upDown
                            || centre.lengthSqr() > (RANGE + 1) * (RANGE + 1)) {
                        continue;
                    }
                    pos.set(at.getX() + dx, at.getY() + lift, at.getZ() + dz);
                    if (!level.isLoaded(pos)) {
                        continue; // never loads a chunk to look into it
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    int faces = 0;
                    for (Direction face : Direction.values()) {
                        Vec3 normal = Vec3.atLowerCornerOf(face.getUnitVec3i());
                        // Turned towards the eye: the eye is on the outside of the face's plane.
                        if (centre.add(normal.scale(0.5)).dot(normal) >= 0) {
                            continue;
                        }
                        beside.setWithOffset(pos, face);
                        BlockState next = level.isLoaded(beside) ? level.getBlockState(beside) : Blocks.AIR.defaultBlockState();
                        boolean open = state.getBlock() instanceof LiquidBlock
                                ? !next.getFluidState().getType().isSame(state.getFluidState().getType()) && Block.shouldRenderFace(state, next, face)
                                : Block.shouldRenderFace(state, next, face);
                        if (open) {
                            faces |= 1 << face.get3DDataValue();
                        }
                    }
                    if (faces != 0) {
                        seen.add(new Seen(dx, lift, dz, Block.getId(state), faces));
                    }
                }
            }
        }
        return seen;
    }
}
