package qbotics.client;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import qbotics.block.PortBlockEntity;
import qbotics.network.Medium;

/**
 * The colour of the collar and ring on a plate: white for items, blue-grey for fluid, red for
 * energy. Which a plate carries is not in the cable's block state (that would multiply its
 * states far beyond what the game can hold), so the ring is drawn white and coloured from
 * what the cable remembers. There is one source per side of the cable; a plate's model on a
 * side uses the source with that side's number.
 */
public final class PlateTints {
    public static final int ITEM = 0xFFF6F6F6;
    public static final int FLUID = 0xFF6080A8;
    public static final int ENERGY = 0xFFC8241C;

    private PlateTints() {
    }

    public static int colour(Medium medium) {
        return switch (medium) {
            case ITEM -> ITEM;
            case FLUID -> FLUID;
            case ENERGY -> ENERGY;
        };
    }

    /** Six sources, in the order of the sides' numbers (down, up, north, south, west, east). */
    public static List<BlockTintSource> sources() {
        List<BlockTintSource> sources = new ArrayList<>(6);
        for (int number = 0; number < 6; number++) {
            Direction side = Direction.from3DDataValue(number);
            sources.add(new BlockTintSource() {
                @Override
                public int color(BlockState state) {
                    return ITEM;
                }

                @Override
                public int colorInWorld(BlockState state, BlockAndTintGetter level, BlockPos pos) {
                    return level.getBlockEntity(pos) instanceof PortBlockEntity host ? colour(host.mediumAt(side)) : ITEM;
                }
            });
        }
        return sources;
    }
}
