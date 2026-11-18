package qbotics.client.display;

import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.camera.Picture;

/**
 * The textures a Qbot's view is drawn with, in the player's game (design doc §8): block textures
 * by name, whole texture files, the textures the game made (downloaded skins), and sprites from
 * the pictures the game keeps for them. Cached.
 */
final class PictureColors {
    private PictureColors() {
    }

    private static final int SKY_TOP = 0x79A6FF;
    private static final int SKY_HORIZON = 0xC0D6FF;

    private static final Map<Identifier, int[]> TEXTURES = new java.util.concurrent.ConcurrentHashMap<>(); // read on the web worker

    /** A whole texture file (ARGB pixels, row by row), as entity models use them. */
    record Image(int[] pixels, int width, int height) {
        /** One white pixel, for a flat colour by tint. */
        static final Image SOLID = new Image(new int[] {0xFFFFFFFF}, 1, 1);
    }

    private static final Map<Identifier, Optional<Image>> IMAGES = new java.util.concurrent.ConcurrentHashMap<>();

    private static final Map<TextureAtlasSprite, Optional<Image>> SPRITES = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * A sprite's picture (its first frame), from the picture the game keeps for it, else from its
     * file (textures/<name>.png); null if neither can be read. Render thread, for the kept picture.
     */
    static @Nullable Image sprite(TextureAtlasSprite sprite) {
        Optional<Image> known = SPRITES.get(sprite);
        if (known != null) {
            return known.orElse(null);
        }
        Image made = null;
        try {
            NativeImage original = sprite.contents().getOriginalImage();
            int w = sprite.contents().width();
            int h = sprite.contents().height();
            int[] pixels = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    pixels[y * w + x] = original.getPixel(x, y);
                }
            }
            made = new Image(pixels, w, h);
        } catch (RuntimeException e) {
            Identifier name = sprite.contents().name();
            made = image(Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png"));
        }
        SPRITES.put(sprite, Optional.ofNullable(made));
        return made;
    }

    /** A texture the game made rather than read from a file (a downloaded skin), by its name; null if none. Render thread. */
    static @Nullable Image loaded(Identifier location) {
        return IMAGES.computeIfAbsent(location, name -> {
            if (Minecraft.getInstance().getTextureManager().getTexture(name) instanceof net.minecraft.client.renderer.texture.DynamicTexture made
                    && made.getPixels() != null) {
                NativeImage image = made.getPixels();
                int w = image.getWidth();
                int h = image.getHeight();
                int[] pixels = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        pixels[y * w + x] = image.getPixel(x, y);
                    }
                }
                return Optional.of(new Image(pixels, w, h));
            }
            return Optional.empty();
        }).orElse(null);
    }

    /** A texture file by its full path (textures/....png); null if it can't be read. Cached. */
    static @Nullable Image image(Identifier file) {
        return IMAGES.computeIfAbsent(file, name -> {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(name);
            if (resource.isEmpty()) {
                return Optional.empty();
            }
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                int w = image.getWidth();
                int h = image.getHeight();
                int[] pixels = new int[w * h];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        pixels[y * w + x] = image.getPixel(x, y);
                    }
                }
                return Optional.of(new Image(pixels, w, h));
            } catch (Exception e) {
                Qbotics.LOGGER.debug("Couldn't read texture {} for a view", name, e);
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** A texture's first frame (a square from its top), ARGB; null if it can't be read. Cached. */
    static int @Nullable [] texture(Identifier sprite) {
        return TEXTURES.computeIfAbsent(sprite, name -> {
            Identifier file = Identifier.fromNamespaceAndPath(name.getNamespace(), "textures/" + name.getPath() + ".png");
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(file);
            if (resource.isEmpty()) {
                return null;
            }
            try (InputStream in = resource.get().open(); NativeImage image = NativeImage.read(in)) {
                int size = image.getWidth();
                int[] pixels = new int[size * size];
                for (int y = 0; y < Math.min(size, image.getHeight()); y++) {
                    for (int x = 0; x < size; x++) {
                        pixels[y * size + x] = image.getPixel(x, y);
                    }
                }
                return pixels;
            } catch (Exception e) {
                Qbotics.LOGGER.debug("Couldn't read texture {} for a picture", file, e);
                return null;
            }
        });
    }




}
