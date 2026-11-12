package qbotics.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import qbotics.laptop.LaptopMenu;
import qbotics.laptop.Laptops;

/** A laptop's two hard-drive slots, labelled with where each drive appears on the laptop. */
public class LaptopScreen extends AbstractContainerScreen<LaptopMenu> {
    public LaptopScreen(LaptopMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, LaptopMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = LaptopMenu.TOP_HEIGHT + 2;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        Panels.panel(graphics, leftPos, topPos, LaptopMenu.TOP_HEIGHT);
        for (int slot = 0; slot < Laptops.DISK_SLOTS; slot++) {
            Panels.slot(graphics, leftPos + LaptopMenu.slotX(slot) - 1, topPos + LaptopMenu.SLOT_Y - 1);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        for (int slot = 0; slot < Laptops.DISK_SLOTS; slot++) {
            Component label = Component.literal("disk" + (slot + 1));
            graphics.text(font, label, LaptopMenu.slotX(slot) + 8 - font.width(label) / 2, LaptopMenu.SLOT_Y + 19, Panels.LABEL, false);
        }
    }
}
