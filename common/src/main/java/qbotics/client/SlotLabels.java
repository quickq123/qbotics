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
    public static void draw(AbstractContainerScreen<?> screen, GuiGraphicsExtractor graphics) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !ProbeItem.showsSlots(minecraft.player)) {
            return;
        }
        for (Slot slot : screen.getMenu().slots) {
            // The player's own inventory isn't what a program reaches; only the block's slots are numbered.
            if (!(slot.container instanceof Inventory)) {
                graphics.text(minecraft.font, String.valueOf(slot.getContainerSlot()), slot.x + 1, slot.y + 1, COLOUR, true);
            }
        }
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
