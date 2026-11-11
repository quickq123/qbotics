package qbotics.neoforge;

import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.AABB;
import qbotics.block.DisplayBlockEntity;
import qbotics.client.display.DisplayRenderer;

/** The display renderer, told how big a screen is so the game doesn't skip it when its corner is out of view. */
final class NeoDisplayRenderer extends DisplayRenderer {
    NeoDisplayRenderer(BlockEntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public AABB getRenderBoundingBox(DisplayBlockEntity display) {
        BlockPos pos = display.getBlockPos();
        if (!display.isOrigin()) {
            return new AABB(pos);
        }
        Direction right = display.facing().getCounterClockWise();
        BlockPos far = pos.relative(right, display.screenWidth() - 1).below(display.screenHeight() - 1);
        return new AABB(pos).minmax(new AABB(far));
    }
}
