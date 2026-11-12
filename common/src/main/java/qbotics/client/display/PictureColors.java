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
 * Colours a Qbot's picture in the player's game (design doc §8): each pixel takes the colour of
 * the block's real texture at the point its ray hit (tinted as grass, leaves and water are where
 * the picture was taken), shaded by the face's direction, the light there and the distance.
 * Creatures are flat shapes in a colour by kind. Render thread.
 */
final class PictureColors {
    private PictureColors() {
    }

    private static final int SKY_TOP = 0x79A6FF;
    private static final int SKY_HORIZON = 0xC0D6FF;

    /** A face's texture: its pixels (ARGB, frame one), size, average colour, and tint (or -1). */
    private record Face(int @Nullable [] pixels, int size, int average, int tint) {
    }

    private static final Map<Long, Face> FACES = new HashMap<>();
    private static final Map<Identifier, int[]> TEXTURES = new HashMap<>();

    static BufferedImage colorize(Picture picture) {
        FACES.clear(); // tints depend on where the picture was taken
        int w = picture.width();
        int h = picture.height();
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        byte[] d = picture.data();
        for (int y = 0; y < h; y++) {
            int sky = mix(SKY_TOP, SKY_HORIZON, y / (float) Math.max(1, h - 1));
            for (int x = 0; x < w; x++) {
                int o = (y * w + x) * Picture.BYTES_PER_PIXEL;
                int kind = d[o];
                int value = ((d[o + 1] & 0xFF) << 24) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 8) | (d[o + 4] & 0xFF);
                if (kind == Picture.SKY) {
                    out.setRGB(x, y, sky);
                    continue;
                }
                Direction face = Direction.from3DDataValue(d[o + 5]);
                int light = d[o + 7] & 0xFF;
                float distance = (d[o + 8] & 0xFF) / 8f;
                int color;
                if (kind == Picture.CREATURE) {
                    color = switch (value) {
                        case Picture.MONSTER -> 0xB03A3A;
                        case Picture.ANIMAL -> 0x6DAA4F;
                        case Picture.PLAYER -> 0x3E7BD6;
                        default -> 0xA0A0A0;
                    };
                } else {
                    int uv = d[o + 6] & 0xFF;
                    color = texel(Block.stateById(value), value, face, uv >> 4, uv & 15, picture);
                }
                float shade = switch (face) {
                    case UP -> 1f;
                    case DOWN -> 0.5f;
                    case NORTH, SOUTH -> 0.8f;
                    default -> 0.64f;
                };
                shade *= 0.25f + 0.75f * Math.min(15, light) / 15f;
                color = scale(color, shade);
                float fog = (float) Math.pow(Math.min(1, distance / (float) qbotics.camera.Camera.RANGE), 1.6) * 0.7f;
                out.setRGB(x, y, mix(color, SKY_HORIZON, fog));
            }
        }
        return out;
    }

    private static int texel(BlockState state, int stateId, Direction direction, int u, int v, Picture picture) {
        Face face = FACES.computeIfAbsent(((long) stateId << 3) | direction.get3DDataValue(), k -> face(state, direction, picture));
        int color;
        if (face.pixels() == null) {
            color = face.average();
        } else {
            int size = face.size();
            int px = Math.min(size - 1, u * size / 16 + size / 32);
            int py = Math.min(size - 1, v * size / 16 + size / 32);
            int argb = face.pixels()[py * size + px];
            color = (argb >>> 24) < 128 ? face.average() : argb & 0xFFFFFF; // see-through bits (leaves, glass) take the average
        }
        return face.tint() < 0 ? color : multiply(color, face.tint());
    }

    private static Face face(BlockState state, Direction direction, Picture picture) {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (!state.getFluidState().isEmpty() && state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock) {
                boolean water = state.getFluidState().is(FluidTags.WATER);
                int[] pixels = texture(Identifier.withDefaultNamespace(water ? "block/water_still" : "block/lava_still"));
                int size = pixels == null ? 1 : (int) Math.sqrt(pixels.length);
                return new Face(pixels, size, average(pixels, water ? 0x3F76E4 : 0xD25A12), water ? 0x3F76E4 : -1);
            }
            BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(42L), parts);
            BakedQuad chosen = null;
            for (BlockStateModelPart part : parts) {
                for (BakedQuad quad : part.getQuads(direction)) {
                    chosen = quad;
                    break;
                }
                if (chosen == null) {
                    for (BakedQuad quad : part.getQuads(null)) {
                        if (quad.direction() == direction) {
                            chosen = quad;
                            break;
                        }
                    }
                }
                if (chosen != null) {
                    break;
                }
            }
            TextureAtlasSprite sprite = chosen != null ? chosen.materialInfo().sprite() : model.particleMaterial().sprite();
            int[] pixels = texture(sprite.contents().name());
            int size = pixels == null ? 1 : (int) Math.sqrt(pixels.length);
            int tint = -1;
            if (chosen != null && chosen.materialInfo().isTinted()) {
                BlockTintSource source = minecraft.getModelManager() == null ? null
                        : minecraft.getBlockColors().getTintSource(state, chosen.materialInfo().tintIndex());
                if (source != null) {
                    tint = minecraft.level != null ? source.colorInWorld(state, minecraft.level, picture.origin()) : source.color(state);
                    tint &= 0xFFFFFF;
                }
            }
            return new Face(pixels, size, average(pixels, state.getMapColor(minecraft.level, picture.origin()).col), tint);
        } catch (RuntimeException e) {
            return new Face(null, 1, 0x808080, -1);
        }
    }

    /** A texture's first frame (a square from its top), ARGB; null if it can't be read. Cached. */
    private static int @Nullable [] texture(Identifier sprite) {
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

    private static int average(int @Nullable [] pixels, int fallback) {
        if (pixels == null) {
            return fallback & 0xFFFFFF;
        }
        long r = 0;
        long g = 0;
        long b = 0;
        long n = 0;
        for (int argb : pixels) {
            if ((argb >>> 24) >= 128) {
                r += (argb >> 16) & 0xFF;
                g += (argb >> 8) & 0xFF;
                b += argb & 0xFF;
                n++;
            }
        }
        return n == 0 ? fallback & 0xFFFFFF : (int) ((r / n) << 16 | (g / n) << 8 | (b / n));
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }

    private static int scale(int color, float f) {
        return (Math.min(255, (int) (((color >> 16) & 0xFF) * f)) << 16) | (Math.min(255, (int) (((color >> 8) & 0xFF) * f)) << 8)
                | Math.min(255, (int) ((color & 0xFF) * f));
    }

    private static int multiply(int color, int tint) {
        return ((((color >> 16) & 0xFF) * ((tint >> 16) & 0xFF) / 255) << 16) | ((((color >> 8) & 0xFF) * ((tint >> 8) & 0xFF) / 255) << 8)
                | ((color & 0xFF) * (tint & 0xFF) / 255);
    }
}
