package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/** A block that works a box in the world, which its screen's Show area outlines. */
public interface HasArea {
    /** The box it works, in world coordinates. */
    AABB area();

    BlockPos getBlockPos();
}
