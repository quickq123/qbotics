package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import qbotics.block.ProgrammerMenu;

/** The Programmer's screen: the drive socket, "Edit files", and how redstone picks a program. */
public class ProgrammerScreen extends AbstractContainerScreen<ProgrammerMenu> {
    public ProgrammerScreen(ProgrammerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, ProgrammerMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = ProgrammerMenu.TOP_HEIGHT + 2;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("screen.qbotics.edit_files"), button -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ProgrammerMenu.EDIT_BUTTON);
            }
        }).bounds(leftPos + ProgrammerMenu.SLOT_X + 26, topPos + ProgrammerMenu.SLOT_Y - 1, 80, 20).build());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        Panels.panel(graphics, leftPos, topPos, ProgrammerMenu.TOP_HEIGHT);
        Panels.slot(graphics, leftPos + ProgrammerMenu.SLOT_X, topPos + ProgrammerMenu.SLOT_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        Component hint = Component.translatable("screen.qbotics.programmer_hint");
        Component hint2 = Component.translatable("screen.qbotics.programmer_hint2");
        graphics.text(font, hint, (imageWidth - font.width(hint)) / 2, ProgrammerMenu.SLOT_Y + 25, Panels.LABEL, false);
        graphics.text(font, hint2, (imageWidth - font.width(hint2)) / 2, ProgrammerMenu.SLOT_Y + 35, Panels.LABEL, false);
    }
}
