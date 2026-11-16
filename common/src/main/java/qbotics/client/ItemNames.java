package qbotics.client;

import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import qbotics.block.ProbeItem;

/**
 * Looking up what an item is called in programs. While a Network Probe is in the hand,
 * every item's tooltip gains a line with its exact name (and, for a bucket, its fluid's
 * name); Ctrl+C copies that name, and holding Shift lists the item's tags, which are what
 * a filter such as "#minecraft:logs" tests, the short data words that hold for that stack
 * (enchant=mending) and, for a stack with data, the exact filter naming it, which Ctrl+Shift+C copies.
 */
public final class ItemNames {
    private static final int KEY_C = 67;
    private static final int MAX_TAGS = 8;
    /** How long after the mouse was last over an item Ctrl+C still means that item. */
    private static final long HOVER_MS = 300;
    private static final long COPIED_MS = 1500;

    /** The name on the tooltip that was drawn last, and when. */
    private static String hovered = "";
    private static long hoveredAt;
    private static String copied = "";
    private static long copiedAt;
    /** The exact filter of the stack under the mouse (Shift held), and the item it is for. */
    private static String exact = "";
    private static String exactFor = "";
    private static final int MAX_EXACT = 120;

    private ItemNames() {
    }

    private static boolean probeInHand() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.player != null && minecraft.player.getMainHandItem().getItem() instanceof ProbeItem;
    }

    /** Adds the lines to an item's tooltip. Called whenever a tooltip is made. */
    public static void tooltip(ItemStack stack, List<Component> lines) {
        if (stack.isEmpty() || !probeInHand()) {
            return;
        }
        long now = System.currentTimeMillis();
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        hovered = name;
        hoveredAt = now;
        lines.add(Component.translatable("tooltip.qbotics.probe.name", Component.literal(name).withStyle(ChatFormatting.YELLOW))
                .withStyle(ChatFormatting.GRAY));
        if (stack.getItem() instanceof BucketItem bucket && bucket.getContent() != Fluids.EMPTY) {
            String fluid = BuiltInRegistries.FLUID.getKey(bucket.getContent()).toString();
            lines.add(Component.translatable("tooltip.qbotics.probe.fluid", Component.literal(fluid).withStyle(ChatFormatting.YELLOW))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (Minecraft.getInstance().hasShiftDown()) {
            // What sets this stack apart from its kind: the short filter words, and a filter naming exactly it.
            List<String> words = qbotics.network.ItemData.words(stack);
            if (!words.isEmpty()) {
                lines.add(Component.translatable("tooltip.qbotics.probe.words", Component.literal(String.join(" ", words)).withStyle(ChatFormatting.LIGHT_PURPLE))
                        .withStyle(ChatFormatting.GRAY));
            }
            if (!stack.getComponentsPatch().isEmpty() && Minecraft.getInstance().level != null) {
                exact = qbotics.network.ItemData.exactFilter(stack, Minecraft.getInstance().level.registryAccess());
                exactFor = name;
                String shown = exact.length() > MAX_EXACT ? exact.substring(0, MAX_EXACT) + "..." : exact;
                lines.add(Component.translatable("tooltip.qbotics.probe.exact", Component.literal(shown).withStyle(ChatFormatting.LIGHT_PURPLE))
                        .withStyle(ChatFormatting.GRAY));
            } else {
                exact = "";
            }
            List<String> tags = stack.typeHolder().tags().map(tag -> "#" + tag.location()).sorted().toList();
            if (tags.isEmpty()) {
                lines.add(Component.translatable("tooltip.qbotics.probe.no_tags").withStyle(ChatFormatting.DARK_GRAY));
            }
            for (int i = 0; i < Math.min(tags.size(), MAX_TAGS); i++) {
                lines.add(Component.literal("  " + tags.get(i)).withStyle(ChatFormatting.AQUA));
            }
            if (tags.size() > MAX_TAGS) {
                lines.add(Component.translatable("tooltip.qbotics.probe.more_tags", tags.size() - MAX_TAGS).withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        boolean justCopied = (name.equals(copied) || (!exact.isEmpty() && exact.equals(copied))) && now - copiedAt < COPIED_MS;
        lines.add(Component.translatable(justCopied ? "tooltip.qbotics.probe.copied" : "tooltip.qbotics.probe.hint")
                .withStyle(justCopied ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
    }

    /** A key was pressed while a screen is open. Ctrl+C over an item, probe in hand, copies the item's name. */
    public static void keyPressed(int key) {
        Minecraft minecraft = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (key == KEY_C && minecraft.hasControlDown() && probeInHand() && now - hoveredAt < HOVER_MS && !hovered.isEmpty()) {
            // With Shift too, the exact filter (data and all) of a stack that has data.
            String text = minecraft.hasShiftDown() && !exact.isEmpty() && exactFor.equals(hovered) ? exact : hovered;
            minecraft.keyboardHandler.setClipboard(text);
            copied = text;
            copiedAt = now;
        }
    }
}
