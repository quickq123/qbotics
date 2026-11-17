package qbotics.client.display;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.model.geom.builders.UVPair;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import qbotics.camera.ConeView;
import qbotics.camera.Picture;
import qbotics.camera.View;

/**
 * Draws a Qbot's {@link View} in software, into an image: each listed block's model faces (the
 * game's own models and textures) and each creature as a box in its kind's colour, seen from the
 * Qbot's eye, nearest in front. Even light (night vision); faces shaded by the way they face, as
 * the game shades them. See-through pixels (glass, leaves) are left out, not blended. Runs on the
 * web panels' worker, off the render thread.
 */
public final class ConeRenderer {
    private ConeRenderer() {
    }

    private static final int SKY_TOP = 0x79A6FF;
    private static final int SKY_HORIZON = 0xC0D6FF;

    /** A face ready to draw: its corners (x, y, z, u, v each), texture, tint and shade. */
    private record Face(double[][] corners, int[] pixels, int size, int tint, float shade) {
    }

    /** Today's picture of the same view, coloured as players' games colour it, to compare. */
    public static BufferedImage oldPicture(Picture picture) {
        return PictureColors.colorize(picture);
    }

    public static BufferedImage render(View shot) {
        return render(shot.blocks(), shot.creatures(), shot.origin(), shot.facing(), shot.pitch(), shot.width(), shot.height());
    }

    public static BufferedImage render(List<ConeView.Seen> seen, BlockPos origin, Direction facing, int width, int height) {
        return render(seen, List.of(), origin, facing, 0, width, height);
    }

    public static BufferedImage render(List<ConeView.Seen> seen, List<ConeView.Creature> creatures, BlockPos origin, Direction facing,
            double pitch, int width, int height) {
        ConeView.Basis basis = ConeView.basis(facing, pitch, height / (double) width);
        double across = basis.across();
        double upDown = basis.upDown();

        int[] color = new int[width * height];
        float[] depth = new float[width * height];
        for (int y = 0; y < height; y++) {
            int sky = mix(SKY_TOP, SKY_HORIZON, y / (float) Math.max(1, height - 1));
            for (int x = 0; x < width; x++) {
                color[y * width + x] = sky;
                depth[y * width + x] = Float.MAX_VALUE;
            }
        }
        Map<Long, Integer> tints = new HashMap<>();
        for (ConeView.Seen block : seen) {
            BlockState state = Block.stateById(block.state());
            for (Face face : faces(state, block, origin, tints)) {
                // Into the eye's space: x right, y up, z ahead.
                draw(face, basis, color, depth, width, height);
            }
        }
        for (ConeView.Creature c : creatures) {
            int kind = switch (c.kind()) {
                case Picture.MONSTER -> 0xB03A3A;
                case Picture.ANIMAL -> 0x6DAA4F;
                case Picture.PLAYER -> 0x3E7BD6;
                default -> 0xA0A0A0;
            };
            for (Direction d : Direction.values()) {
                double[][] corners = box(c, d);
                draw(new Face(corners, new int[] {0xFF000000 | kind}, 1, -1, shade(d)), basis, color, depth, width, height);
            }
        }
        // A little haze far off, so distance reads.
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                float d = depth[i];
                int c = color[i];
                if (d != Float.MAX_VALUE) {
                    c = mix(c, SKY_HORIZON, (float) Math.pow(Math.min(1, d / ConeView.RANGE), 1.6) * 0.6f);
                }
                out.setRGB(x, y, c);
            }
        }
        return out;
    }

    private static void draw(Face face, ConeView.Basis basis, int[] color, float[] depth, int width, int height) {
        double[][] view = new double[4][];
        for (int i = 0; i < 4; i++) {
            double[] c = face.corners()[i];
            double[] v = basis.toView(c[0], c[1], c[2]);
            view[i] = new double[] {v[0], v[1], v[2], c[3], c[4]};
        }
        List<double[]> polygon = ConeView.clipNear(view, 5);
        for (int i = 1; i + 1 < polygon.size(); i++) {
            triangle(polygon.get(0), polygon.get(i), polygon.get(i + 1), face, color, depth, width, height, basis.across(), basis.upDown());
        }
    }

    /** A creature's box's face. */
    private static double[][] box(ConeView.Creature c, Direction d) {
        double x0 = c.x();
        double y0 = c.y();
        double z0 = c.z();
        double x1 = x0 + c.width();
        double y1 = y0 + c.height();
        double z1 = z0 + c.depth();
        double[][] p = switch (d) {
            case UP -> new double[][] {{x0, y1, z0}, {x0, y1, z1}, {x1, y1, z1}, {x1, y1, z0}};
            case DOWN -> new double[][] {{x0, y0, z1}, {x0, y0, z0}, {x1, y0, z0}, {x1, y0, z1}};
            case NORTH -> new double[][] {{x1, y1, z0}, {x1, y0, z0}, {x0, y0, z0}, {x0, y1, z0}};
            case SOUTH -> new double[][] {{x0, y1, z1}, {x0, y0, z1}, {x1, y0, z1}, {x1, y1, z1}};
            case WEST -> new double[][] {{x0, y1, z0}, {x0, y0, z0}, {x0, y0, z1}, {x0, y1, z1}};
            case EAST -> new double[][] {{x1, y1, z1}, {x1, y0, z1}, {x1, y0, z0}, {x1, y1, z0}};
        };
        double[][] corners = new double[4][];
        for (int i = 0; i < 4; i++) {
            corners[i] = new double[] {p[i][0], p[i][1], p[i][2], 0.5, 0.5};
        }
        return corners;
    }

    /** The faces of one listed block to draw: those of its model the server said could be seen, and its unculled ones. */
    private static List<Face> faces(BlockState state, ConeView.Seen block, BlockPos origin, Map<Long, Integer> tints) {
        List<Face> faces = new ArrayList<>();
        Minecraft minecraft = Minecraft.getInstance();
        if (state.getBlock() instanceof LiquidBlock) {
            boolean water = state.getFluidState().is(FluidTags.WATER);
            int[] pixels = PictureColors.texture(Identifier.withDefaultNamespace(water ? "block/water_still" : "block/lava_still"));
            if (pixels == null) {
                return faces;
            }
            int size = (int) Math.sqrt(pixels.length);
            for (Direction d : Direction.values()) {
                if ((block.faces() & (1 << d.get3DDataValue())) != 0) {
                    faces.add(new Face(cube(block, d, d == Direction.UP ? 0.875 : 1), pixels, size, water ? 0x3F76E4 : -1, shade(d)));
                }
            }
            return faces;
        }
        BlockStateModel model = minecraft.getModelManager().getBlockStateModelSet().get(state);
        List<BlockStateModelPart> parts = new ArrayList<>();
        model.collectParts(RandomSource.create(42L), parts);
        for (BlockStateModelPart part : parts) {
            for (Direction d : Direction.values()) {
                if ((block.faces() & (1 << d.get3DDataValue())) != 0) {
                    for (BakedQuad quad : part.getQuads(d)) {
                        add(faces, quad, state, block, origin, tints);
                    }
                }
            }
            for (BakedQuad quad : part.getQuads(null)) {
                add(faces, quad, state, block, origin, tints);
            }
        }
        return faces;
    }

    private static void add(List<Face> faces, BakedQuad quad, BlockState state, ConeView.Seen block, BlockPos origin, Map<Long, Integer> tints) {
        TextureAtlasSprite sprite = quad.materialInfo().sprite();
        int[] pixels = PictureColors.texture(sprite.contents().name());
        if (pixels == null) {
            return;
        }
        int size = (int) Math.sqrt(pixels.length);
        double u0 = sprite.getU0();
        double du = sprite.getU1() - u0;
        double v0 = sprite.getV0();
        double dv = sprite.getV1() - v0;
        double[][] corners = new double[4][];
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            long uv = quad.packedUV(i);
            corners[i] = new double[] {block.dx() + p.x(), block.dy() + p.y(), block.dz() + p.z(),
                    (UVPair.unpackU(uv) - u0) / du, (UVPair.unpackV(uv) - v0) / dv};
        }
        int tint = -1;
        if (quad.materialInfo().isTinted()) {
            long key = ((long) block.state() << 8) | quad.materialInfo().tintIndex();
            tint = tints.computeIfAbsent(key, k -> {
                Minecraft minecraft = Minecraft.getInstance();
                BlockTintSource source = minecraft.getBlockColors().getTintSource(state, quad.materialInfo().tintIndex());
                if (source == null) {
                    return -1;
                }
                try {
                    return (minecraft.level != null ? source.colorInWorld(state, minecraft.level, origin) : source.color(state)) & 0xFFFFFF;
                } catch (RuntimeException e) {
                    return source.color(state) & 0xFFFFFF; // the place isn't known here (another thread, far away): the usual colour
                }
            });
        }
        faces.add(new Face(corners, pixels, size, tint, quad.materialInfo().shade() ? shade(quad.direction()) : 1f));
    }

    /** A whole cube's face (water, lava), its top at {@code top}. */
    private static double[][] cube(ConeView.Seen b, Direction d, double top) {
        double x = b.dx();
        double y = b.dy();
        double z = b.dz();
        double[][] p = switch (d) {
            case UP -> new double[][] {{0, top, 0}, {0, top, 1}, {1, top, 1}, {1, top, 0}};
            case DOWN -> new double[][] {{0, 0, 1}, {0, 0, 0}, {1, 0, 0}, {1, 0, 1}};
            case NORTH -> new double[][] {{1, top, 0}, {1, 0, 0}, {0, 0, 0}, {0, top, 0}};
            case SOUTH -> new double[][] {{0, top, 1}, {0, 0, 1}, {1, 0, 1}, {1, top, 1}};
            case WEST -> new double[][] {{0, top, 0}, {0, 0, 0}, {0, 0, 1}, {0, top, 1}};
            case EAST -> new double[][] {{1, top, 1}, {1, 0, 1}, {1, 0, 0}, {1, top, 0}};
        };
        double[][] uv = {{0, 0}, {0, 1}, {1, 1}, {1, 0}};
        double[][] corners = new double[4][];
        for (int i = 0; i < 4; i++) {
            corners[i] = new double[] {x + p[i][0], y + p[i][1], z + p[i][2], uv[i][0], uv[i][1]};
        }
        return corners;
    }

    private static float shade(Direction d) {
        return switch (d) {
            case UP -> 1f;
            case DOWN -> 0.5f;
            case NORTH, SOUTH -> 0.8f;
            default -> 0.6f;
        };
    }

    private static void triangle(double[] a, double[] b, double[] c, Face face, int[] color, float[] depth, int w, int h,
            double across, double upDown) {
        double[][] v = {a, b, c};
        double[] sx = new double[3];
        double[] sy = new double[3];
        double[] iz = new double[3];
        for (int i = 0; i < 3; i++) {
            sx[i] = (v[i][0] / v[i][2] / across + 1) * w / 2;
            sy[i] = (1 - v[i][1] / v[i][2] / upDown) * h / 2;
            iz[i] = 1 / v[i][2];
        }
        double area = (sx[1] - sx[0]) * (sy[2] - sy[0]) - (sx[2] - sx[0]) * (sy[1] - sy[0]);
        if (Math.abs(area) < 1e-9) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(sx[0], Math.min(sx[1], sx[2]))));
        int maxX = Math.min(w - 1, (int) Math.ceil(Math.max(sx[0], Math.max(sx[1], sx[2]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(sy[0], Math.min(sy[1], sy[2]))));
        int maxY = Math.min(h - 1, (int) Math.ceil(Math.max(sy[0], Math.max(sy[1], sy[2]))));
        for (int py = minY; py <= maxY; py++) {
            double y = py + 0.5;
            for (int px = minX; px <= maxX; px++) {
                double x = px + 0.5;
                double w0 = ((sx[1] - x) * (sy[2] - y) - (sx[2] - x) * (sy[1] - y)) / area;
                double w1 = ((sx[2] - x) * (sy[0] - y) - (sx[0] - x) * (sy[2] - y)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    continue;
                }
                double inv = w0 * iz[0] + w1 * iz[1] + w2 * iz[2];
                float z = (float) (1 / inv);
                int i = py * w + px;
                if (z >= depth[i]) {
                    continue;
                }
                double u = (w0 * a[3] * iz[0] + w1 * b[3] * iz[1] + w2 * c[3] * iz[2]) / inv;
                double t = (w0 * a[4] * iz[0] + w1 * b[4] * iz[1] + w2 * c[4] * iz[2]) / inv;
                int size = face.size();
                int tx = Math.clamp((int) (u * size), 0, size - 1);
                int ty = Math.clamp((int) (t * size), 0, size - 1);
                int argb = face.pixels()[ty * size + tx];
                if ((argb >>> 24) < 128) {
                    continue; // see-through: what is behind shows
                }
                int rgb = argb & 0xFFFFFF;
                if (face.tint() >= 0) {
                    rgb = multiply(rgb, face.tint());
                }
                color[i] = scale(rgb, face.shade());
                depth[i] = z;
            }
        }
    }

    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return r << 16 | g << 8 | bl;
    }

    private static int multiply(int a, int b) {
        return (((a >> 16) & 0xFF) * ((b >> 16) & 0xFF) / 255) << 16 | (((a >> 8) & 0xFF) * ((b >> 8) & 0xFF) / 255) << 8
                | (a & 0xFF) * (b & 0xFF) / 255;
    }

    private static int scale(int c, float f) {
        return Math.min(255, (int) (((c >> 16) & 0xFF) * f)) << 16 | Math.min(255, (int) (((c >> 8) & 0xFF) * f)) << 8
                | Math.min(255, (int) ((c & 0xFF) * f));
    }
}
