package qbotics.laptop;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import qbotics.QboticsConfig;

/**
 * A laptop (design doc §5.6): a player's own computer, carried. Right-click in the air opens
 * it (editor, terminal, files); sneak and right-click in the air opens its two hard-drive
 * slots. Every laptop a player uses opens the same computer: their one laptop shell and files.
 */
public class LaptopItem extends Item {
    public LaptopItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResult.SUCCESS;
        }
        if (!QboticsConfig.laptopsEnabled) {
            serverPlayer.sendOverlayMessage(Component.translatable("message.qbotics.laptop.disabled"));
            return InteractionResult.FAIL;
        }
        if (player.isSecondaryUseActive()) {
            serverPlayer.openMenu(new SimpleMenuProvider((id, inventory, who) -> new LaptopMenu(id, inventory, hand),
                    Component.translatable("screen.qbotics.laptop.disks")));
        } else {
            Laptops.open(serverPlayer, player.getItemInHand(hand));
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Right-click a computer or Qbot with the laptop: a direct connection, its editor and
     * terminal, as if you were at its keyboard (for a Qbot, the only way in by hand). Only your
     * own; for anyone else's, ssh in, if its owner allows.
     */
    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        Level level = context.getLevel();
        if (!(level.getBlockEntity(context.getClickedPos()) instanceof qbotics.block.ComputerBlockEntity computer)) {
            return InteractionResult.PASS;
        }
        if (!(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.SUCCESS;
        }
        return connect(player, computer);
    }

    /** Opens a computer or Qbot's editor and terminal for this player, if they may. */
    public static InteractionResult connect(ServerPlayer player, qbotics.block.ComputerBlockEntity computer) {
        if (!QboticsConfig.laptopsEnabled) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.laptop.disabled"));
            return InteractionResult.FAIL;
        }
        if (computer.bricked()) {
            player.sendOverlayMessage(qbotics.limits.Limits.tryActivate(player.level(), computer, player));
            return InteractionResult.SUCCESS;
        }
        if (!player.getUUID().equals(computer.owner())) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.laptop.not_yours"));
            return InteractionResult.FAIL;
        }
        qbotics.computer.ComputerManager.get(player.level().getServer()).openTerminal(player, computer);
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
        lines.accept(Component.translatable("item.qbotics.laptop.tip").withStyle(net.minecraft.ChatFormatting.GRAY));
        long disks = Laptops.disks(stack).nonEmptyItemCopyStream().count();
        if (disks > 0) {
            lines.accept(Component.translatable("item.qbotics.laptop.disks", disks).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }
}
