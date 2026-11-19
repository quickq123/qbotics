package qbotics.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * How every slot number in the mod is drawn: half the font's size, tucked into the slot's
 * top-left corner, so the item and its count underneath stay easy to see.
 */
public final class SlotNumbers {
    private SlotNumbers() {
    }

    /** The number for the slot whose top-left corner (the 16x16 item area) is at x, y. */
    public static void draw(GuiGraphicsExtractor graphics, Font font, int number, int x, int y, int colour) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(0.5F, 0.5F);
        graphics.text(font, String.valueOf(number), 0, 0, colour, true);
        graphics.pose().popMatrix();
    }
}
