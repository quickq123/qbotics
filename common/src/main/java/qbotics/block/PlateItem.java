package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import qbotics.QboticsContent;
import qbotics.network.Medium;
import qbotics.network.NetworkDevice;

/**
 * An Input, Output or Bus in the hand: a plate that goes on a cable and points at the
 * block next to it (design doc §11.0).
 *
 * <p>Click the block the plate should work on (a chest, a furnace): the plate goes on the
 * cable in front of that face, pointing at it. If there is no cable there yet, one is taken
 * from the player's inventory and placed with the plate. Clicking a cable itself puts the
 * plate on the side that was clicked.
 */
public class PlateItem extends Item {
    private final NetworkDevice.Kind kind;
    private final Medium medium;

    public PlateItem(NetworkDevice.Kind kind, Medium medium, Item.Properties properties) {
        super(properties);
        this.kind = kind;
        this.medium = medium;
    }

    public Medium medium() {
        return medium;
    }

    public NetworkDevice.Kind kind() {
        return kind;
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        BlockPos clicked = context.getClickedPos();
        Direction face = context.getClickedFace();
        boolean onCable = level.getBlockState(clicked).getBlock() instanceof CableBlock;
        // On a cable: the side that was clicked. On anything else: the cable in front of it, pointing back at it.
        BlockPos pos = onCable ? clicked : clicked.relative(face);
        Direction side = onCable ? face : face.getOpposite();
        BlockState there = level.getBlockState(pos);
        boolean cableThere = there.getBlock() instanceof CableBlock;
        if (cableThere && CableBlock.plateKind(level, pos, side) != null) {
            return fail(player, "message.qbotics.plate_taken");
        }
        if (!cableThere && !there.canBeReplaced()) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (!cableThere) {
            // No cable yet: a plate can't hang in the air, so one of the player's cables goes with it.
            Block cable = QboticsContent.CABLE_BLOCK.get();
            if (player != null && !player.hasInfiniteMaterials()) {
                int slot = player.getInventory().findSlotMatchingItem(new ItemStack(cable));
                if (slot < 0) {
                    return fail(player, "message.qbotics.plate_needs_cable");
                }
                player.getInventory().removeItem(slot, 1);
            }
            level.setBlock(pos, CableBlock.withConnections(level, pos, cable.defaultBlockState()), Block.UPDATE_ALL);
        }
        CablePort port = CableBlock.attach(level, pos, kind, medium, side);
        if (port == null) {
            return InteractionResult.FAIL;
        }
        port.onPlaced(player == null ? null : player.getUUID());
        // A plate renamed in an anvil is a device with that name.
        Component custom = context.getItemInHand().get(DataComponents.CUSTOM_NAME);
        if (custom != null) {
            port.setDeviceName(custom.getString());
        }
        level.playSound(null, pos, SoundEvents.METAL_PLACE, SoundSource.BLOCKS, 1.0f, 1.0f);
        if (player == null || !player.hasInfiniteMaterials()) {
            context.getItemInHand().shrink(1);
        }
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult fail(Player player, String message) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendOverlayMessage(Component.translatable(message));
        }
        return InteractionResult.FAIL;
    }
}
