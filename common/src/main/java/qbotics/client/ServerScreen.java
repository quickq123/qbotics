package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import qbotics.block.ServerMenu;
import qbotics.upgrade.ChunkLoaders;

/** A Server's hardware: four drive sockets, a Chunk Loader Upgrade slot and its switch, and what chunk loading is doing. */
public class ServerScreen extends AbstractContainerScreen<ServerMenu> {
    private Button toggle;

    public ServerScreen(ServerMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, Panels.WIDTH, ServerMenu.TOP_HEIGHT + 96);
        this.inventoryLabelY = ServerMenu.TOP_HEIGHT + 2;
    }

    @Override
    protected void init() {
        super.init();
        toggle = addRenderableWidget(Button.builder(label(), button -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.gameMode != null) {
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, ServerMenu.TOGGLE_BUTTON);
            }
        }).bounds(leftPos + 36, topPos + ServerMenu.UPGRADE_Y - 1, 108, 20).build());
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
        Panels.panel(graphics, leftPos, topPos, ServerMenu.TOP_HEIGHT);
        for (int slot = 0; slot < ServerMenu.DRIVES; slot++) {
            Panels.slot(graphics, leftPos + ServerMenu.slotX(slot), topPos + ServerMenu.SLOT_Y);
        }
        Panels.slot(graphics, leftPos + ServerMenu.slotX(0), topPos + ServerMenu.UPGRADE_Y);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        graphics.text(font, Component.translatable("screen.qbotics.server.disks_hint"), 100, ServerMenu.SLOT_Y + 1, Panels.LABEL, false);
        graphics.text(font, Component.translatable("screen.qbotics.server.disks_hint2"), 100, ServerMenu.SLOT_Y + 10, Panels.LABEL, false);
        ChunkLoaders.Status status = ChunkLoaders.Status.of(menu.status());
        Component line = status == ChunkLoaders.Status.LOADING
                ? (menu.loaded() < menu.wanted()
                        ? Component.translatable("screen.qbotics.antenna.status.capped", menu.loaded(), menu.wanted())
                        : Component.translatable("screen.qbotics.antenna.status.loading", menu.loaded()))
                : Component.translatable(status == ChunkLoaders.Status.INACTIVE ? "screen.qbotics.server.status.inactive"
                        : "screen.qbotics.antenna.status." + status.word);
        graphics.text(font, line, 10, ServerMenu.UPGRADE_Y + 26, Panels.LABEL, false);
    }
}
