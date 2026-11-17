package qbotics.upgrade;

import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;

/**
 * An upgrade: an item fitted into a device's upgrade slots. Antennas take them now; Qbots will
 * take the same items later, so what an upgrade does belongs to the upgrade, not the device.
 */
public class UpgradeItem extends Item {
    /** The kinds of upgrade. */
    public enum Kind {
        /** Carries items, fluid and energy between this network and others (both ends need one). */
        TRANSPORT("transport"),
        /** Keeps the chunks of the device's network loaded (a Qbot: the chunks it is in). */
        CHUNK_LOADER("chunk_loader"),
        /** A Qbot's only: it senses blocks up to 4 away in every direction, not just the six touching it. */
        SENSE("sense");

        /** The word programs use. */
        public final String word;

        Kind(String word) {
            this.word = word;
        }
    }

    private final Kind kind;

    public UpgradeItem(Kind kind, Item.Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** The kind of upgrade a stack is, or null if it isn't one. */
    public static Kind kindOf(ItemStack stack) {
        return stack.getItem() instanceof UpgradeItem upgrade ? upgrade.kind : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display,
            Consumer<Component> lines, TooltipFlag flag) {
        lines.accept(Component.translatable("item.qbotics." + kind.word + "_upgrade.tooltip").withStyle(ChatFormatting.GRAY));
        lines.accept(Component.translatable("item.qbotics." + kind.word + "_upgrade.fits").withStyle(ChatFormatting.DARK_GRAY));
    }
}
