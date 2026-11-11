package qbotics.client;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import qbotics.block.InputMenu;

/**
 * The Input block's screen. It is the game's own two-row chest picture with the top row
 * darkened: those nine are filter sockets, not places to put items (see {@link InputMenu}).
 */
public class InputScreen extends AbstractContainerScreen<InputMenu> {
    private static final Identifier CHEST = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    private static final int ROWS = 2;
    /** The colours of a socket: darker than a slot's 0xFF8B8B8B, with a darker rim. */
    private static final int SOCKET = 0xFF4A4A4A;
    private static final int SOCKET_RIM = 0xFF2B2B2B;

    public InputScreen(InputMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 114 + ROWS * 18);
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = (this.width - this.imageWidth) / 2;
        int y = (this.height - this.imageHeight) / 2;
        // The chest picture in two pieces: the title and our rows, then the player's inventory.
        graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, x, y, 0.0F, 0.0F, this.imageWidth, ROWS * 18 + 17, 256, 256);
        graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, x, y + ROWS * 18 + 17, 0.0F, 126.0F, this.imageWidth, 96, 256, 256);
        for (int slot = 0; slot < InputMenu.SLOTS; slot++) {
            int left = x + 8 + slot * 18;
            int top = y + 18;
            graphics.fill(left, top, left + 16, top + 16, SOCKET_RIM);
            graphics.fill(left + 1, top + 1, left + 15, top + 15, SOCKET);
        }
    }
}
