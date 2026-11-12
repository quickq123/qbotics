package qbotics.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * A Qbot's camera (design doc §8): a ray from its front through every pixel of the picture,
 * like a tiny ray tracer, on the server. The rays see blocks (and fluids) and the shapes of
 * creatures; each pixel records what was hit, where, how lit and how far, and players' games
 * colour it. Pictures are kept for a while by number, so programs can put them on screens.
 */
public final class Camera {
    private Camera() {
    }

    public static final int MAX_WIDTH = 128;
    public static final int MAX_HEIGHT = 96;
    /** How far the camera sees, in blocks. */
    public static final double RANGE = 32;
    /** How wide it sees, across, in degrees. */
    private static final double FOV = 70;
    /** Pictures kept for screens; the oldest go first. */
    private static final int KEPT = 256;

    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final Map<Integer, Picture> PICTURES = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Picture> eldest) {
            return size() > KEPT;
        }
    };

    public static synchronized Picture get(int id) {
        return PICTURES.get(id);
    }

    private static synchronized int keep(Picture picture) {
        int id = NEXT.getAndIncrement();
        PICTURES.put(id, picture);
        return id;
    }

    /**
     * Takes a picture from a block's face, looking the way it faces (tilted up or down by pitch
     * degrees), and keeps it. Returns what a program gets: its number, size, and what each pixel
     * shows (a list of names, and a string of one character per pixel indexing it).
     */
    public static JsonObject snapshot(ServerLevel level, BlockPos at, Direction facing, int width, int height, double pitch) {
        width = Math.clamp(width, 1, MAX_WIDTH);
        height = Math.clamp(height, 1, MAX_HEIGHT);
        Picture picture = take(level, at, facing, width, height, Math.clamp(pitch, -80, 80));
        int id = keep(picture);
        // What each pixel shows, for programs: a palette of names and an index per pixel.
        List<String> names = new ArrayList<>();
        Map<String, Integer> index = new LinkedHashMap<>();
        StringBuilder pixels = new StringBuilder(width * height);
        byte[] d = picture.data();
        for (int i = 0; i < width * height; i++) {
            int o = i * Picture.BYTES_PER_PIXEL;
            int kind = d[o];
            int value = ((d[o + 1] & 0xFF) << 24) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 8) | (d[o + 4] & 0xFF);
            String name = kind == Picture.SKY ? "" : kind == Picture.BLOCK
                    ? BuiltInRegistries.BLOCK.getKey(Block.stateById(value).getBlock()).toString()
                    : CREATURE_NAMES[Math.clamp(value, 0, CREATURE_NAMES.length - 1)];
            Integer n = index.get(name);
            if (n == null) {
                n = index.size() < 255 ? index.size() : 254;
                if (index.size() < 255) {
                    index.put(name, n);
                    names.add(name);
                }
            }
            pixels.append((char) (0x100 + n)); // one character per pixel, never a quote or a backslash
        }
        JsonObject reply = new JsonObject();
        reply.addProperty("id", id);
        reply.addProperty("width", width);
        reply.addProperty("height", height);
        JsonArray list = new JsonArray();
        names.forEach(list::add);
        reply.add("names", list);
        reply.addProperty("pixels", pixels.toString());
        return reply;
    }

    private static final String[] CREATURE_NAMES = {"monster", "animal", "player", "creature"};

    static Picture take(ServerLevel level, BlockPos at, Direction facing, int width, int height, double pitch) {
        Vec3 forward = Vec3.atLowerCornerOf(facing.getUnitVec3i());
        Vec3 right = Vec3.atLowerCornerOf(facing.getClockWise().getUnitVec3i());
        Vec3 up = new Vec3(0, 1, 0);
        double tilt = Math.toRadians(pitch);
        Vec3 look = forward.scale(Math.cos(tilt)).add(up.scale(Math.sin(tilt)));
        Vec3 lookUp = up.scale(Math.cos(tilt)).subtract(forward.scale(Math.sin(tilt)));
        Vec3 eye = Vec3.atCenterOf(at).add(forward.scale(0.51));
        double half = Math.tan(Math.toRadians(FOV / 2));
        double aspect = height / (double) width;

        // The creatures in sight, gathered once: each ray is tried against their boxes.
        AABB around = new AABB(eye, eye).inflate(RANGE);
        List<Entity> creatures = level.getEntities((Entity) null, around, e -> e.isAlive() && !e.isSpectator());

        byte[] data = new byte[width * height * Picture.BYTES_PER_PIXEL];
        for (int py = 0; py < height; py++) {
            double sy = (1 - 2 * (py + 0.5) / height) * half * aspect;
            for (int px = 0; px < width; px++) {
                double sx = (2 * (px + 0.5) / width - 1) * half;
                Vec3 dir = look.add(right.scale(sx)).add(lookUp.scale(sy)).normalize();
                Vec3 end = eye.add(dir.scale(RANGE));
                BlockHitResult hit = see(level, eye, end, dir);
                double blockDistance = hit.getType() == HitResult.Type.MISS ? Double.MAX_VALUE : hit.getLocation().distanceTo(eye);
                Entity seen = null;
                double seenDistance = blockDistance;
                for (Entity creature : creatures) {
                    var through = creature.getBoundingBox().clip(eye, end);
                    if (through.isPresent()) {
                        double d = through.get().distanceTo(eye);
                        if (d < seenDistance) {
                            seen = creature;
                            seenDistance = d;
                        }
                    }
                }
                int o = (py * width + px) * Picture.BYTES_PER_PIXEL;
                if (seen != null) {
                    data[o] = Picture.CREATURE;
                    putInt(data, o + 1, kindOf(seen));
                    data[o + 5] = (byte) Direction.getApproximateNearest(dir.x, dir.y, dir.z).getOpposite().get3DDataValue();
                    data[o + 6] = 0;
                    data[o + 7] = (byte) level.getMaxLocalRawBrightness(seen.blockPosition());
                    data[o + 8] = (byte) Math.min(255, (int) (seenDistance * 8));
                } else if (hit.getType() != HitResult.Type.MISS) {
                    BlockPos pos = hit.getBlockPos();
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() && !level.getFluidState(pos).isEmpty()) {
                        state = level.getFluidState(pos).createLegacyBlock();
                    }
                    Direction face = hit.getDirection();
                    Vec3 local = hit.getLocation().subtract(pos.getX(), pos.getY(), pos.getZ());
                    double u;
                    double v;
                    switch (face) {
                        case UP, DOWN -> {
                            u = local.x;
                            v = local.z;
                        }
                        case NORTH -> {
                            u = 1 - local.x;
                            v = 1 - local.y;
                        }
                        case SOUTH -> {
                            u = local.x;
                            v = 1 - local.y;
                        }
                        case WEST -> {
                            u = local.z;
                            v = 1 - local.y;
                        }
                        default -> {
                            u = 1 - local.z;
                            v = 1 - local.y;
                        }
                    }
                    int iu = Math.clamp((int) (u * 16), 0, 15);
                    int iv = Math.clamp((int) (v * 16), 0, 15);
                    data[o] = Picture.BLOCK;
                    putInt(data, o + 1, Block.getId(state));
                    data[o + 5] = (byte) face.get3DDataValue();
                    data[o + 6] = (byte) ((iu << 4) | iv);
                    data[o + 7] = (byte) level.getMaxLocalRawBrightness(pos.relative(face));
                    data[o + 8] = (byte) Math.min(255, (int) (blockDistance * 8));
                } else {
                    data[o] = Picture.SKY;
                }
            }
        }
        return new Picture(width, height, at.immutable(), data);
    }

    /** Blocks nobody ever sees, which the camera looks through. */
    private static boolean unseen(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.BARRIER) || state.is(net.minecraft.world.level.block.Blocks.LIGHT)
                || state.is(net.minecraft.world.level.block.Blocks.STRUCTURE_VOID);
    }

    /** The first block a ray sees, carrying on past the ones nobody sees (a few at most). */
    private static BlockHitResult see(ServerLevel level, Vec3 from, Vec3 end, Vec3 dir) {
        Vec3 start = from;
        for (int skips = 0; ; skips++) {
            BlockHitResult hit = level.clip(new ClipContext(start, end, ClipContext.Block.VISUAL, ClipContext.Fluid.ANY, CollisionContext.empty()));
            if (hit.getType() == HitResult.Type.MISS || skips >= 8 || !unseen(level.getBlockState(hit.getBlockPos()))
                    || !level.getFluidState(hit.getBlockPos()).isEmpty()) {
                return hit;
            }
            // On from just past the block it went into.
            BlockPos inside = hit.getBlockPos();
            Vec3 at = hit.getLocation();
            for (int step = 0; step < 40 && BlockPos.containing(at).equals(inside); step++) {
                at = at.add(dir.scale(0.05));
            }
            if (at.distanceToSqr(from) >= end.distanceToSqr(from)) {
                return BlockHitResult.miss(end, Direction.UP, BlockPos.containing(end));
            }
            start = at;
        }
    }

    private static int kindOf(Entity entity) {
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

    private static void putInt(byte[] data, int at, int value) {
        data[at] = (byte) (value >>> 24);
        data[at + 1] = (byte) (value >>> 16);
        data[at + 2] = (byte) (value >>> 8);
        data[at + 3] = (byte) value;
    }
}
