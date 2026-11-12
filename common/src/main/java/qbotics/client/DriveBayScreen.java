package qbotics.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import qbotics.block.DriveBayMenu;

/** The Drive Bay's screen: four sockets for hard drives, and a line saying where they appear. */
public class DriveBayScreen extends AbstractContainerScreen<DriveBayMenu> {
    public DriveBayScreen(DriveBayMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, DriveBayMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = DriveBayMenu.TOP_HEIGHT + 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        Panels.panel(graphics, leftPos, topPos, DriveBayMenu.TOP_HEIGHT);
        for (int slot = 0; slot < DriveBayMenu.SLOTS; slot++) {
            Panels.slot(graphics, leftPos + DriveBayMenu.slotX(slot), topPos + DriveBayMenu.SLOT_Y);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        Component hint = Component.translatable("screen.qbotics.drive_bay_hint");
        Component hint2 = Component.translatable("screen.qbotics.drive_bay_hint2");
        graphics.text(font, hint, (imageWidth - font.width(hint)) / 2, DriveBayMenu.SLOT_Y + 22, Panels.LABEL, false);
        graphics.text(font, hint2, (imageWidth - font.width(hint2)) / 2, DriveBayMenu.SLOT_Y + 31, Panels.LABEL, false);
    }
}
