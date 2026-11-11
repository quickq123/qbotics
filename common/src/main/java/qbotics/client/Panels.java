package qbotics.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Drawing shared by the stores' screens: a panel in the game's own style above the player's
 * inventory, slots, filter sockets, and fluid drawn in its own texture and colour.
 */
final class Panels {
    private static final Identifier CHEST = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png");
    static final int WIDTH = 176;
    static final int BACKGROUND = 0xFFC6C6C6;
    private static final int SLOT = 0xFF8B8B8B;
    private static final int SHADOW = 0xFF373737;
    private static final int LIGHT = 0xFFFFFFFF;
    /** A socket is darker than a slot, with a darker rim, as on the item Interface. */
    private static final int SOCKET = 0xFF4A4A4A;
    private static final int SOCKET_RIM = 0xFF2B2B2B;
    static final int LABEL = 0xFF404040;

    private Panels() {
    }

    /**
     * The whole background: a plain panel of the given height for the block's part (its top
     * edge and sides taken from the chest picture), then the player's inventory below it.
     */
    static void panel(GuiGraphicsExtractor graphics, int x, int y, int topHeight) {
        // The chest picture's title band (its top edge and corners), then its side edges, stretched down.
        graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, x, y, 0.0F, 0.0F, WIDTH, 17, 256, 256);
        for (int row = 17; row < topHeight; row += 16) {
            int height = Math.min(16, topHeight - row);
            graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, x, y + row, 0.0F, 17.0F, 7, height, 256, 256);
            graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, x + WIDTH - 7, y + row, WIDTH - 7.0F, 17.0F, 7, height, 256, 256);
        }
        graphics.fill(x + 7, y + 17, x + WIDTH - 7, y + topHeight, BACKGROUND);
        // The player's inventory, as the chest picture draws it.
        graphics.blit(RenderPipelines.GUI_TEXTURED, CHEST, x, y + topHeight, 0.0F, 126.0F, WIDTH, 96, 256, 256);
    }

    /** A sunken box, as the game draws a slot: dark top and left, light bottom and right. */
    static void sunken(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int inside) {
        graphics.fill(x, y, x + width, y + height, SHADOW);
        graphics.fill(x + 1, y + 1, x + width, y + height, LIGHT);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, inside);
    }

    /** An 18 by 18 slot whose item sits at (x + 1, y + 1). */
    static void slot(GuiGraphicsExtractor graphics, int x, int y) {
        sunken(graphics, x, y, 18, 18, SLOT);
    }

    /** A filter socket: the 16 by 16 square an item is drawn on, dark. */
    static void socket(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.fill(x, y, x + 16, y + 16, SOCKET_RIM);
        graphics.fill(x + 1, y + 1, x + 15, y + 15, SOCKET);
    }

    /** Fills a box with a fluid's still texture, in its colour, tile by tile from the bottom up. */
    static void fluid(GuiGraphicsExtractor graphics, Fluid fluid, int x, int y, int width, int height) {
        if (fluid == Fluids.EMPTY || height <= 0) {
            return;
        }
        FluidModel model = Minecraft.getInstance().getModelManager().getFluidStateModelSet().get(fluid.defaultFluidState());
        TextureAtlasSprite sprite = model.stillMaterial().sprite();
        int colour = 0xFFFFFFFF;
        if (model.tintSource() != null) {
            // Water takes its colour from the biome, so ask where the player is.
            var state = fluid.defaultFluidState().createLegacyBlock();
            Minecraft minecraft = Minecraft.getInstance();
            colour = 0xFF000000 | (minecraft.level != null && minecraft.player != null
                    ? model.tintSource().colorInWorld(state, minecraft.level, minecraft.player.blockPosition())
                    : model.tintSource().color(state));
        }
        for (int bottom = y + height; bottom > y; bottom -= 16) {
            int tile = Math.min(16, bottom - y);
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, bottom - tile, width, tile, colour);
        }
    }

    /** A fluid's name as players know it ("Water"), from its block; its ID if it has none. */
    static Component fluidName(Fluid fluid) {
        var block = fluid.defaultFluidState().createLegacyBlock().getBlock();
        return block == Blocks.AIR ? Component.literal(BuiltInRegistries.FLUID.getKey(fluid).toString()) : block.getName();
    }
}
