package qbotics.camera;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Where a Qbot's views (design doc §8, {@link ConeView}) are kept for a while by number, so
 * programs can put them on screens. (Until 2026-11-18 the camera traced a ray a pixel on the
 * server; the view took over from it.)
 */
public final class Camera {
    private Camera() {
    }

    /** The largest grid of names a program is given (a bigger view names squares of pixels). */
    public static final int MAX_WIDTH = 128;
    public static final int MAX_HEIGHT = 96;
    /** Views kept for screens; the oldest go first. */
    private static final int KEPT = 256;

    private static final AtomicInteger NEXT = new AtomicInteger(1);
    private static final Map<Integer, Shot> PICTURES = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Shot> eldest) {
            return size() > KEPT;
        }
    };

    /** A kept view, or null. */
    public static synchronized Shot shot(int id) {
        return PICTURES.get(id);
    }

    static synchronized int keep(Shot shot) {
        int id = NEXT.getAndIncrement();
        PICTURES.put(id, shot);
        return id;
    }

    /** Blocks nobody sees, which the view looks through: barriers, light blocks, structure voids. */
    static boolean unseen(BlockState state) {
        return state.is(Blocks.BARRIER) || state.is(Blocks.LIGHT) || state.is(Blocks.STRUCTURE_VOID);
    }
}
