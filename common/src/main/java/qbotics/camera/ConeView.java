package qbotics.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A Qbot's view (qbot.view(), built 2026-11-17 alongside {@link Camera}): instead of tracing a ray
 * for every pixel, the server lists the blocks in the Qbot's cone of view that have a face it could
 * see (a face turned towards its eye, and not covered by the block beside it) and the creatures in
 * it, and each player's game draws those with the game's own models and textures
 * (qbotics.client.display.ConeRenderer). What is hidden further back is sorted out by the
 * drawing's depth, so the server never traces a ray. No light: the Qbot sees as with night vision.
 *
 * <p>Programs still learn what each pixel shows (block_at, counts): the server draws the same
 * faces as plain shapes into a grid of names, which costs far less than tracing rays.
 */
public final class ConeView {
    /** How far ahead it sees, in blocks. */
    public static final int RANGE = 24;
    /** Across, in degrees (as the camera). */
    public static final double FOV = 70;
    public static final int MAX_WIDTH = 256;
    public static final int MAX_HEIGHT = 192;
    /** At most this many creatures are listed (the nearest). */
    public static final int MAX_CREATURES = 48;

    private static final String[] CREATURE_NAMES = {"monster", "animal", "player", "creature"};

    private ConeView() {
    }

    /**
     * A block it sees: where (blocks from the Qbot, along the world's axes), what (a block state
     * ID), which of its faces could be seen (a bit for each Direction's 3D data value), and the
     * water or lava in it if any (see {@link #packFluid}; 0 for none).
     */
    public record Seen(int dx, int dy, int dz, int state, int faces, int fluid) {
        public Seen(int dx, int dy, int dz, int state, int faces) {
            this(dx, dy, dz, state, faces, 0);
        }

        /** The fluid's faces that could be seen (bits as {@link #faces}). */
        public int fluidFaces() {
            return fluid & 0x3F;
        }

        /** The height of the fluid's surface at a corner (0 north-west, 1 north-east, 2 south-east, 3 south-west), 0 to 1. */
        public float fluidHeight(int corner) {
            return ((fluid >>> (6 + corner * 6)) & 0x3F) / 32f;
        }
    }

    /** Packs a fluid's faces and corner heights (north-west, north-east, south-east, south-west) for {@link Seen#fluid}. */
    public static int packFluid(int faces, float[] heights) {
        int packed = 1 << 30 | faces & 0x3F;
        for (int i = 0; i < 4; i++) {
            packed |= Math.clamp(Math.round(heights[i] * 32), 0, 32) << (6 + i * 6);
        }
        return packed;
    }

    /**
     * A creature in sight: its box (from the Qbot's block's corner, in blocks), kind
     * (Picture.MONSTER...), what it is (an entity type's ID, so players' games draw its model; -1 if
     * not known), the way its body faces (degrees, as the game's yaw) and whether it is young.
     */
    public record Creature(float x, float y, float z, float width, float height, float depth, int kind, int type, float yaw, boolean baby) {
        public Creature(float x, float y, float z, float width, float height, float depth, int kind) {
            this(x, y, z, width, height, depth, kind, -1, 0, false);
        }
    }

    /**
     * The eye and the way it looks: forward (tilted by pitch degrees, up positive), right, the
     * picture's up, and how far across and up the picture reaches at one block ahead.
     */
    public record Basis(Vec3 eye, Vec3 forward, Vec3 right, Vec3 up, double across, double upDown) {
        /** A point (from the Qbot's block's corner) in the eye's space: x right, y up, z ahead. */
        public double[] toView(double x, double y, double z) {
            double px = x - eye.x;
            double py = y - eye.y;
            double pz = z - eye.z;
            return new double[] {px * right.x + py * right.y + pz * right.z, px * up.x + py * up.y + pz * up.z,
                    px * forward.x + py * forward.y + pz * forward.z};
        }
    }

    public static Basis basis(Direction facing, double pitch, double aspect) {
        Vec3 ahead = Vec3.atLowerCornerOf(facing.getUnitVec3i());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getUnitVec3i());
        Vec3 sky = new Vec3(0, 1, 0);
        double tilt = Math.toRadians(Math.clamp(pitch, -80, 80));
        Vec3 forward = ahead.scale(Math.cos(tilt)).add(sky.scale(Math.sin(tilt)));
        Vec3 up = sky.scale(Math.cos(tilt)).subtract(ahead.scale(Math.sin(tilt)));
        Vec3 eye = new Vec3(0.5, 0.5, 0.5).add(ahead.scale(0.51));
        double across = Math.tan(Math.toRadians(FOV / 2));
        return new Basis(eye, forward, right, up, across, across * aspect);
    }

    /** What a Qbot at {@code at}, facing {@code facing}, sees level, in a picture height/width {@code aspect}. */
    public static List<Seen> collect(ServerLevel level, BlockPos at, Direction facing, double aspect) {
        return collect(level, at, basis(facing, 0, aspect));
    }

    /** The blocks with a face it could see, nearest first. */
    public static List<Seen> collect(ServerLevel level, BlockPos at, Basis b) {
        double margin = 0.87; // half a block's diagonal: a block partly in view counts
        // The box around the cone: the eye and the far corners.
        double minX = b.eye().x;
        double maxX = minX;
        double minY = b.eye().y;
        double maxY = minY;
        double minZ = b.eye().z;
        double maxZ = minZ;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                Vec3 corner = b.eye().add(b.forward().add(b.right().scale(sx * b.across())).add(b.up().scale(sy * b.upDown())).scale(RANGE));
                minX = Math.min(minX, corner.x);
                maxX = Math.max(maxX, corner.x);
                minY = Math.min(minY, corner.y);
                maxY = Math.max(maxY, corner.y);
                minZ = Math.min(minZ, corner.z);
                maxZ = Math.max(maxZ, corner.z);
            }
        }
        List<Seen> seen = new ArrayList<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos beside = new BlockPos.MutableBlockPos();
        for (int dx = (int) Math.floor(minX) - 1; dx <= (int) Math.floor(maxX) + 1; dx++) {
            for (int dy = (int) Math.floor(minY) - 1; dy <= (int) Math.floor(maxY) + 1; dy++) {
                for (int dz = (int) Math.floor(minZ) - 1; dz <= (int) Math.floor(maxZ) + 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue; // itself
                    }
                    double[] v = b.toView(dx + 0.5, dy + 0.5, dz + 0.5);
                    double z = v[2];
                    if (z <= -margin || Math.abs(v[0]) - margin > (z + margin) * b.across()
                            || Math.abs(v[1]) - margin > (z + margin) * b.upDown() || z > RANGE + margin) {
                        continue;
                    }
                    pos.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                    if (!level.isLoaded(pos)) {
                        continue; // never loads a chunk to look into it
                    }
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || Camera.unseen(state)) {
                        continue; // nothing, or a block nobody sees (a barrier, light, structure void)
                    }
                    int faces = 0;
                    int fluidFaces = 0;
                    boolean liquid = state.getBlock() instanceof LiquidBlock;
                    net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
                    for (Direction face : Direction.values()) {
                        // Turned towards the eye: the eye is on the outside of the face's plane.
                        double fx = dx + 0.5 + face.getStepX() * 0.5 - b.eye().x;
                        double fy = dy + 0.5 + face.getStepY() * 0.5 - b.eye().y;
                        double fz = dz + 0.5 + face.getStepZ() * 0.5 - b.eye().z;
                        boolean turned = fx * face.getStepX() + fy * face.getStepY() + fz * face.getStepZ() < 0;
                        // A fluid's top may be lower than its block's: kept either way, its height sorts it out.
                        if (!turned && !(face == Direction.UP && !fluid.isEmpty())) {
                            continue;
                        }
                        beside.setWithOffset(pos, face);
                        BlockState next = level.isLoaded(beside) ? level.getBlockState(beside) : Blocks.AIR.defaultBlockState();
                        if (Camera.unseen(next)) {
                            next = Blocks.AIR.defaultBlockState(); // seen through, so it covers nothing
                        }
                        if (!fluid.isEmpty() && !next.getFluidState().getType().isSame(fluid.getType())
                                && (face == Direction.UP || !next.isSolidRender())) {
                            fluidFaces |= 1 << face.get3DDataValue();
                        }
                        if (turned && !liquid && Block.shouldRenderFace(state, next, face)) {
                            faces |= 1 << face.get3DDataValue();
                        }
                    }
                    if (faces != 0 || fluidFaces != 0) {
                        seen.add(new Seen(dx, dy, dz, Block.getId(state), faces,
                                fluidFaces == 0 ? 0 : packFluid(fluidFaces, fluidHeights(level, pos.immutable(), fluid.getType()))));
                    }
                }
            }
        }
        seen.sort(java.util.Comparator.comparingInt(s -> s.dx() * s.dx() + s.dy() * s.dy() + s.dz() * s.dz()));
        return seen;
    }

    /** A fluid's surface at its four corners (north-west, north-east, south-east, south-west), as the game draws it. */
    static float[] fluidHeights(ServerLevel level, BlockPos pos, net.minecraft.world.level.material.Fluid type) {
        float self = height(level, type, pos);
        if (self >= 1) {
            return new float[] {1, 1, 1, 1};
        }
        float north = height(level, type, pos.north());
        float south = height(level, type, pos.south());
        float east = height(level, type, pos.east());
        float west = height(level, type, pos.west());
        return new float[] {corner(level, type, self, north, west, pos.north().west()), corner(level, type, self, north, east, pos.north().east()),
                corner(level, type, self, south, east, pos.south().east()), corner(level, type, self, south, west, pos.south().west())};
    }

    private static float corner(ServerLevel level, net.minecraft.world.level.material.Fluid type, float self, float a, float c, BlockPos cornerPos) {
        if (a >= 1 || c >= 1) {
            return 1;
        }
        float[] weighted = new float[2];
        if (a > 0 || c > 0) {
            float h = height(level, type, cornerPos);
            if (h >= 1) {
                return 1;
            }
            weigh(weighted, h);
        }
        weigh(weighted, self);
        weigh(weighted, a);
        weigh(weighted, c);
        return weighted[0] / weighted[1];
    }

    private static void weigh(float[] weighted, float height) {
        if (height >= 0.8f) {
            weighted[0] += height * 10;
            weighted[1] += 10;
        } else if (height >= 0) {
            weighted[0] += height;
            weighted[1]++;
        }
    }

    /** As the game: 1 under the same fluid, its own height, 0 beside air, -1 beside a solid block. */
    private static float height(ServerLevel level, net.minecraft.world.level.material.Fluid type, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return 0;
        }
        BlockState state = level.getBlockState(pos);
        if (type.isSame(state.getFluidState().getType())) {
            BlockPos above = pos.above();
            return level.isLoaded(above) && type.isSame(level.getFluidState(above).getType()) ? 1 : state.getFluidState().getOwnHeight();
        }
        return state.isSolid() ? -1 : 0;
    }

    /** The creatures in the cone (not hidden ones: the drawing's depth hides those), nearest first. */
    public static List<Creature> creatures(ServerLevel level, BlockPos at, Basis b) {
        Vec3 eye = Vec3.atLowerCornerOf(at).add(b.eye());
        List<Entity> near = level.getEntities((Entity) null, new AABB(eye, eye).inflate(RANGE),
                e -> e.isAlive() && !e.isSpectator() && !e.isInvisible());
        near.sort(java.util.Comparator.comparingDouble(e -> e.distanceToSqr(eye)));
        List<Creature> list = new ArrayList<>();
        for (Entity entity : near) {
            AABB box = entity.getBoundingBox();
            Vec3 c = box.getCenter();
            double[] v = b.toView(c.x - at.getX(), c.y - at.getY(), c.z - at.getZ());
            double margin = Math.max(box.getXsize(), box.getYsize()) / 2 + 0.5;
            if (v[2] <= 0 || Math.abs(v[0]) - margin > (v[2] + margin) * b.across() || Math.abs(v[1]) - margin > (v[2] + margin) * b.upDown()
                    || v[2] > RANGE) {
                continue;
            }
            float yaw = entity instanceof net.minecraft.world.entity.LivingEntity living ? living.yBodyRot : entity.getYRot();
            boolean baby = entity instanceof net.minecraft.world.entity.LivingEntity living && living.isBaby();
            list.add(new Creature((float) (box.minX - at.getX()), (float) (box.minY - at.getY()), (float) (box.minZ - at.getZ()),
                    (float) box.getXsize(), (float) box.getYsize(), (float) box.getZsize(), kindOf(entity),
                    BuiltInRegistries.ENTITY_TYPE.getId(entity.getType()), yaw, baby));
            if (list.size() >= MAX_CREATURES) {
                break;
            }
        }
        return list;
    }

    static int kindOf(Entity entity) {
        if (entity instanceof Player) {
            return Picture.PLAYER;
        }
        MobCategory category = entity.getType().getCategory();
        if (category == MobCategory.MONSTER) {
            return Picture.MONSTER;
        }
        if (category == MobCategory.MISC) {
            return Picture.OTHER;
        }
        return Picture.ANIMAL;
    }

    /** Takes a view and keeps it for screens; returns what a program gets (as a picture: its number, size, and what each pixel shows). */
    public static JsonObject take(ServerLevel level, BlockPos at, Direction facing, int width, int height, double pitch) {
        width = Math.clamp(width, 1, MAX_WIDTH);
        height = Math.clamp(height, 1, MAX_HEIGHT);
        Basis b = basis(facing, pitch, height / (double) width);
        List<Seen> blocks = collect(level, at, b);
        List<Creature> creatures = creatures(level, at, b);
        View view = new View(width, height, at.immutable(), facing, (float) Math.clamp(pitch, -80, 80), List.copyOf(blocks), List.copyOf(creatures));
        int id = Camera.keep(view);
        return names(level, at, b, view, id);
    }

    /**
     * What each pixel shows, for programs: the same faces drawn as plain shapes into a grid of
     * names (a palette, and one character a pixel indexing it), as a picture gives them.
     */
    private static JsonObject names(ServerLevel level, BlockPos at, Basis b, View view, int id) {
        // A program's answer must stay small: past 128 x 96 each name covers a square of pixels (step x step).
        int step = Math.max(1, Math.max(ceilDiv(view.width(), Camera.MAX_WIDTH), ceilDiv(view.height(), Camera.MAX_HEIGHT)));
        int w = ceilDiv(view.width(), step);
        int h = ceilDiv(view.height(), step);
        int[] which = new int[w * h]; // 0 sky, else palette index + 1
        float[] depth = new float[w * h];
        java.util.Arrays.fill(depth, Float.MAX_VALUE);
        Map<String, Integer> palette = new LinkedHashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Seen s : view.blocks()) {
            BlockState state = Block.stateById(s.state());
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
            int index = palette.computeIfAbsent(name, k -> palette.size()) + 1;
            List<AABB> boxes;
            if (state.getBlock() instanceof LiquidBlock) {
                float top = s.fluid() == 0 ? 0.875f
                        : Math.max(0.05f, (s.fluidHeight(0) + s.fluidHeight(1) + s.fluidHeight(2) + s.fluidHeight(3)) / 4);
                boxes = List.of(new AABB(0, 0, 0, 1, top, 1));
            } else {
                pos.set(at.getX() + s.dx(), at.getY() + s.dy(), at.getZ() + s.dz());
                boxes = state.getShape(level, pos).toAabbs();
                if (boxes.isEmpty()) {
                    boxes = List.of(new AABB(0.25, 0, 0.25, 0.75, 0.5, 0.75)); // flowers, grass: something to point at
                }
            }
            for (AABB box : boxes) {
                drawBox(b, box.move(s.dx(), s.dy(), s.dz()), s.faces() | s.fluidFaces(), index, which, depth, w, h);
            }
        }
        for (Creature c : view.creatures()) {
            int index = palette.computeIfAbsent(CREATURE_NAMES[Math.clamp(c.kind(), 0, 3)], k -> palette.size()) + 1;
            drawBox(b, new AABB(c.x(), c.y(), c.z(), c.x() + c.width(), c.y() + c.height(), c.z() + c.depth()), 0x3F, index, which, depth, w, h);
        }
        List<String> names = new ArrayList<>();
        names.add("");
        names.addAll(palette.keySet());
        StringBuilder pixels = new StringBuilder(w * h);
        for (int i = 0; i < w * h; i++) {
            pixels.append((char) (0x100 + Math.min(which[i], 254)));
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("id", id);
        reply.addProperty("width", view.width());
        reply.addProperty("height", view.height());
        reply.addProperty("step", step);
        JsonArray list = new JsonArray();
        names.subList(0, Math.min(names.size(), 255)).forEach(list::add);
        reply.add("names", list);
        reply.addProperty("pixels", pixels.toString());
        return reply;
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    /** Draws the faces of a box turned towards the eye (those on the block's outside only if listed as seen). */
    private static void drawBox(Basis b, AABB box, int seenFaces, int index, int[] which, float[] depth, int w, int h) {
        for (Direction face : Direction.values()) {
            double[][] q = corners(box, face);
            // Turned away from the eye: not drawn.
            double nx = face.getStepX();
            double ny = face.getStepY();
            double nz = face.getStepZ();
            if ((q[0][0] - b.eye().x) * nx + (q[0][1] - b.eye().y) * ny + (q[0][2] - b.eye().z) * nz >= 0) {
                continue;
            }
            boolean onEdge = switch (face) {
                case DOWN -> box.minY == Math.floor(box.minY);
                case UP -> box.maxY == Math.floor(box.maxY);
                case NORTH -> box.minZ == Math.floor(box.minZ);
                case SOUTH -> box.maxZ == Math.floor(box.maxZ);
                case WEST -> box.minX == Math.floor(box.minX);
                case EAST -> box.maxX == Math.floor(box.maxX);
            };
            if (onEdge && (seenFaces & (1 << face.get3DDataValue())) == 0) {
                continue; // covered by the block beside it
            }
            double[][] view = new double[4][];
            for (int i = 0; i < 4; i++) {
                view[i] = b.toView(q[i][0], q[i][1], q[i][2]);
            }
            List<double[]> polygon = clipNear(view, 3);
            for (int i = 1; i + 1 < polygon.size(); i++) {
                fill(polygon.get(0), polygon.get(i), polygon.get(i + 1), index, which, depth, w, h, b.across(), b.upDown());
            }
        }
    }

    private static double[][] corners(AABB a, Direction face) {
        return switch (face) {
            case DOWN -> new double[][] {{a.minX, a.minY, a.minZ}, {a.maxX, a.minY, a.minZ}, {a.maxX, a.minY, a.maxZ}, {a.minX, a.minY, a.maxZ}};
            case UP -> new double[][] {{a.minX, a.maxY, a.minZ}, {a.minX, a.maxY, a.maxZ}, {a.maxX, a.maxY, a.maxZ}, {a.maxX, a.maxY, a.minZ}};
            case NORTH -> new double[][] {{a.minX, a.minY, a.minZ}, {a.minX, a.maxY, a.minZ}, {a.maxX, a.maxY, a.minZ}, {a.maxX, a.minY, a.minZ}};
            case SOUTH -> new double[][] {{a.minX, a.minY, a.maxZ}, {a.maxX, a.minY, a.maxZ}, {a.maxX, a.maxY, a.maxZ}, {a.minX, a.maxY, a.maxZ}};
            case WEST -> new double[][] {{a.minX, a.minY, a.minZ}, {a.minX, a.minY, a.maxZ}, {a.minX, a.maxY, a.maxZ}, {a.minX, a.maxY, a.minZ}};
            case EAST -> new double[][] {{a.maxX, a.minY, a.minZ}, {a.maxX, a.maxY, a.minZ}, {a.maxX, a.maxY, a.maxZ}, {a.maxX, a.minY, a.maxZ}};
        };
    }

    /** The near plane every drawing cuts at, in blocks ahead of the eye. */
    public static final double NEAR = 0.05;

    /** Cuts off what is behind (or too near) the eye; each point's third value is its depth, the rest are carried along. */
    public static List<double[]> clipNear(double[][] polygon, int size) {
        List<double[]> out = new ArrayList<>(6);
        for (int i = 0; i < polygon.length; i++) {
            double[] a = polygon[i];
            double[] c = polygon[(i + 1) % polygon.length];
            boolean aIn = a[2] >= NEAR;
            boolean cIn = c[2] >= NEAR;
            if (aIn) {
                out.add(a);
            }
            if (aIn != cIn) {
                double t = (NEAR - a[2]) / (c[2] - a[2]);
                double[] m = new double[size];
                for (int k = 0; k < size; k++) {
                    m[k] = a[k] + (c[k] - a[k]) * t;
                }
                out.add(m);
            }
        }
        return out;
    }

    private static void fill(double[] a, double[] c, double[] d, int index, int[] which, float[] depth, int w, int h, double across, double upDown) {
        double[][] v = {a, c, d};
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
                float z = (float) (1 / (w0 * iz[0] + w1 * iz[1] + w2 * iz[2]));
                int i = py * w + px;
                if (z < depth[i]) {
                    depth[i] = z;
                    which[i] = index;
                }
            }
        }
    }
}
