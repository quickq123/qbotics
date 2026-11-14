package qbotics.tablet;

import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.minecraft.world.level.Level;
import qbotics.display.Tablets;

/**
 * A tablet (design doc §10.6): a screen in the hand, with an Infinite antenna inside.
 * Sneak and right-click a network's antenna to pair with it (again to unpair); each paired
 * network gets a tab whose screen its programs draw on. Right-click in the air to open it.
 */
public class TabletItem extends Item {
    /** The tabs a tablet showed when last held, for its tooltip: set by the player's game. */
    public static java.util.function.Function<ItemStack, java.util.List<String>> tooltipTabs = stack -> java.util.List.of();

    public TabletItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer serverPlayer) {
            Tablets.get(serverPlayer.level().getServer()).open(serverPlayer, player.getItemInHand(hand));
        }
        return InteractionResult.SUCCESS;
    }

    /** Sneak and right-click an antenna: pair with (or unpair from) its network. A Qbot: pair with the Qbot itself. */
    @Override
    public InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
        if (context.getPlayer() == null || !context.getPlayer().isSecondaryUseActive()) {
            return InteractionResult.PASS;
        }
        var block = context.getLevel().getBlockEntity(context.getClickedPos());
        if (block instanceof qbotics.block.AntennaBlockEntity antenna) {
            if (context.getPlayer() instanceof ServerPlayer player) {
                Tablets.get(player.level().getServer()).pair(player, context.getItemInHand(), antenna);
            }
            return InteractionResult.SUCCESS;
        }
        if (block instanceof qbotics.qbot.QbotBlockEntity qbot) {
            if (context.getPlayer() instanceof ServerPlayer player) {
                Tablets.get(player.level().getServer()).pair(player, context.getItemInHand(), qbot);
            }
            return InteractionResult.SUCCESS;
        }
        return InteractionResult.PASS;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> lines, TooltipFlag flag) {
        lines.accept(Component.translatable("item.qbotics.tablet.tip").withStyle(net.minecraft.ChatFormatting.GRAY));
        java.util.List<String> tabs = tooltipTabs.apply(stack);
        if (!tabs.isEmpty()) {
            lines.accept(Component.translatable("item.qbotics.tablet.paired", String.join(", ", tabs)).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
    }
}
