package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import qbotics.block.AntennaMenu;
import qbotics.upgrade.ChunkLoaders;

/** An antenna's screen: two upgrade slots, the chunk loading switch, and what chunk loading is doing. */
public class AntennaScreen extends AbstractContainerScreen<AntennaMenu> {
    private Button toggle;

    public AntennaScreen(AntennaMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, AntennaMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = AntennaMenu.TOP_HEIGHT + 2;
    }

    @Override
    protected void init() {
        super.init();
        toggle = addRenderableWidget(Button.builder(label(), button -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, AntennaMenu.TOGGLE_BUTTON);
            }
        }).bounds(leftPos + 60, topPos + AntennaMenu.SLOT_Y - 1, 108, 20).build());
    }

    private Component label() {
        return Component.translatable(menu.chunkLoading() ? "screen.qbotics.antenna.loading_on" : "screen.qbotics.antenna.loading_off");
    }

    @Override
    public void containerTick() {
        super.containerTick();
        toggle.setMessage(label());
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        Panels.panel(graphics, leftPos, topPos, AntennaMenu.TOP_HEIGHT);
        for (int slot = 0; slot < AntennaMenu.SLOTS; slot++) {
            Panels.slot(graphics, leftPos + AntennaMenu.slotX(slot), topPos + AntennaMenu.SLOT_Y);
        }
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        graphics.text(font, Component.translatable("screen.qbotics.antenna.upgrades"), 10, AntennaMenu.SLOT_Y + 22, Panels.LABEL, false);
        ChunkLoaders.Status status = ChunkLoaders.Status.of(menu.status());
        Component line = status == ChunkLoaders.Status.LOADING
                ? (menu.loaded() < menu.wanted()
                        ? Component.translatable("screen.qbotics.antenna.status.capped", menu.loaded(), menu.wanted())
                        : Component.translatable("screen.qbotics.antenna.status.loading", menu.loaded()))
                : Component.translatable("screen.qbotics.antenna.status." + status.word);
        graphics.text(font, Component.translatable("screen.qbotics.antenna.chunks"), 10, AntennaMenu.SLOT_Y + 36, Panels.LABEL, false);
        graphics.text(font, line, 10, AntennaMenu.SLOT_Y + 46, Panels.LABEL, false);
    }
}
