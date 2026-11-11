package qbotics.block;

import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import qbotics.QboticsContent;

/**
 * A hard drive (design doc §17.3): storage that goes in a Drive Bay. Its files are kept on the
 * server under the ID it is given the first time it goes into a bay, and travel with the item.
 * A name given in an anvil is the folder name it has under its bay.
 */
public class HardDriveItem extends Item {
    /** How much a hard drive holds. */
    public static final long CAPACITY_BYTES = 4L * 1024 * 1024;

    public HardDriveItem(Item.Properties properties) {
        super(properties.stacksTo(1));
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, TooltipDisplay display, Consumer<Component> lines,
            TooltipFlag flag) {
        UUID media = stack.get(QboticsContent.MEDIA_ID.get());
        lines.accept(Component.translatable(media == null ? "item.qbotics.hard_drive.blank" : "item.qbotics.hard_drive.used",
                CAPACITY_BYTES / (1024 * 1024)).withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
