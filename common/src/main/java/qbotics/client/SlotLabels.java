package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import qbotics.Qbotics;
import qbotics.block.ProbeItem;
import qbotics.net.ProbePayloads;

/**
 * The Network Probe's "Slots" mode, in the player's game: while a probe in that mode is in
 * the hand, every container screen shows each slot's number, counted from 0. Those are the
 * numbers a program gives as Slot(n). Also the sneak-and-scroll that changes the mode.
 */
public final class SlotLabels {
    private static final int COLOUR = 0xFFFFE85C;

    private SlotLabels() {
    }

    /**
     * Draws the numbers over a container screen's slots. Called while that screen draws its
     * foreground, where positions are measured from the screen's own top-left corner.
     */
    public static void draw(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics, int panelWidth, int panelTop) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !ProbeItem.showsSlots(minecraft.player)) {
            return;
        }
        java.util.Set<net.minecraft.world.Container> inventories = new java.util.HashSet<>();
        for (Slot slot : screen.getMenu().slots) {
            if (!(slot.container instanceof Inventory)) {
                inventories.add(slot.container);
            }
        }
        // One inventory on the screen (a chest, a furnace): its own slot numbers, which a plate
        // reaching it directly uses. Several (a crafting table's grid and its result): the
        // screen's numbers, which a bus working the screen uses. Our Interface's two rows are
        // one row of filters over the slots they belong to, so both show the slot's number.
        boolean byScreen = inventories.size() > 1 && !(screen.getMenu() instanceof qbotics.block.InterfaceMenu);
        for (Slot slot : screen.getMenu().slots) {
            // The player's own inventory isn't what a program reaches; only the block's slots are numbered.
            if (!(slot.container instanceof Inventory)) {
                graphics.text(minecraft.font, String.valueOf(byScreen ? slot.index : slot.getContainerSlot()), slot.x + 1, slot.y + 1, COLOUR, true);
            }
        }
        drawButtonNotice(graphics, minecraft, panelWidth, panelTop);
    }

    /** How long the "Button n" notice stays up after a click. */
    private static final long BUTTON_NOTICE_MILLIS = 1500;
    private static int lastButton = -1;
    private static long lastButtonAt;

    /**
     * A button on an open screen was clicked: the game is about to send its number to the
     * server. With a probe in Slots mode in hand, that number is shown over the screen for a
     * moment and written to the player's own chat, since it is what a program passes to
     * screen.press(). Only screens that use the game's own button message pass through here;
     * a button that shows nothing can't be pressed by a program.
     */
    public static void buttonPressed(int button) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !ProbeItem.showsSlots(minecraft.player)) {
            return;
        }
        lastButton = button;
        lastButtonAt = System.currentTimeMillis();
        String title = minecraft.screen == null ? "this screen" : minecraft.screen.getTitle().getString();
        minecraft.player.sendSystemMessage(net.minecraft.network.chat.Component.literal("[Probe] Button " + button + " on " + title
                + ". In a program: screen.press(" + button + ")  or  recipe.button(" + button + ")")
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
    }

    /** Positions are measured from the panel's top-left corner, as in the rest of the foreground. */
    private static void drawButtonNotice(GuiGraphicsExtractor graphics, Minecraft minecraft, int panelWidth, int panelTop) {
        if (lastButton < 0 || System.currentTimeMillis() - lastButtonAt > BUTTON_NOTICE_MILLIS) {
            return;
        }
        String text = "Button " + lastButton;
        int width = minecraft.font.width(text);
        int x = (panelWidth - width) / 2;
        // Just above the screen's panel, or inside its top edge if there is no room above.
        int y = Math.max(-panelTop + 4, -16);
        graphics.fill(x - 4, y - 3, x + width + 4, y + minecraft.font.lineHeight + 2, 0xE0000000);
        graphics.text(minecraft.font, text, x, y, COLOUR, true);
    }

    /**
     * The mouse wheel turned. With a probe in hand and sneak held, that changes the probe's
     * mode instead of the selected hotbar slot. Returns true if the wheel was used up.
     */
    public static boolean scrolled(double amount) {
        Minecraft minecraft = Minecraft.getInstance();
        if (amount == 0 || minecraft.player == null || minecraft.screen != null || !minecraft.player.isShiftKeyDown()
                || !(minecraft.player.getMainHandItem().getItem() instanceof ProbeItem)) {
            return false;
        }
        int mode = ProbeItem.mode(minecraft.player.getMainHandItem());
        Qbotics.platform().sendToServer(new ProbePayloads.Mode(Math.floorMod(mode + (amount > 0 ? 1 : -1), ProbeItem.MODES)));
        return true;
    }
}
