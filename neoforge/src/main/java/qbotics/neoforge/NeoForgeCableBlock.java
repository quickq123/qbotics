package qbotics.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import qbotics.block.CableBlock;

/**
 * The cable, with NeoForge's hook for "a player broke this block". Breaking a cable while
 * aiming at one of its plates takes off only that plate; the hook is the one place a block
 * can say "I was not removed", on the server and in the player's game alike.
 */
public class NeoForgeCableBlock extends CableBlock {
    public NeoForgeCableBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public boolean onDestroyedByPlayer(BlockState state, Level level, BlockPos pos, Player player, ItemStack tool,
            boolean willHarvest, FluidState fluid) {
        if (CableBlock.breakPlateInstead(state, level, pos, player)) {
            return false;
        }
        return super.onDestroyedByPlayer(state, level, pos, player, tool, willHarvest, fluid);
    }

    /**
     * Redstone dust joins to a cable only where it carries a Logic Pin. {@code direction}
     * points from the dust towards the cable.
     */
    @Override
    public boolean canConnectRedstone(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos,
            net.minecraft.core.@org.jspecify.annotations.Nullable Direction direction) {
        return direction != null && qbotics.block.WorldPlates.pinOn(level, pos, direction.getOpposite());
    }
}
