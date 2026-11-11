package qbotics.network;

import java.util.List;
import net.minecraft.world.level.material.Fluid;

/**
 * Somewhere fluid can be read, put in and taken out: a Fluid Interface's own tanks, or any
 * tank seen through one of its faces. Each loader supplies the second kind with its
 * standard fluid API, so other mods' machines work without special support.
 *
 * <p>Amounts are in millibuckets: 1000 is one bucket. Fluids that carry extra data (other
 * mods' potions, for example) are treated by their plain kind only.
 */
public interface FluidPort {
    int BUCKET = 1000;

    /** One tank: what is in it, how much, and how much it can hold. An empty tank holds Fluids.EMPTY. */
    record Tank(Fluid fluid, int amount, int capacity) {
    }

    List<Tank> tanks();

    /**
     * Puts in up to max millibuckets of a fluid.
     *
     * @param simulate true to only work out the answer, changing nothing
     * @return how much went in
     */
    int insert(Fluid fluid, int max, boolean simulate);

    /** Takes out up to max millibuckets of a fluid. Returns how much came out. */
    int extract(Fluid fluid, int max, boolean simulate);

    /**
     * Moves up to an amount of a fluid from one place to another and returns how much moved.
     * It is tried out before it is done, so nothing is lost or copied when the destination is
     * full or refuses the fluid.
     */
    static int move(FluidPort from, FluidPort to, Fluid fluid, int amount) {
        int available = from.extract(fluid, amount, true);
        int fits = available == 0 ? 0 : to.insert(fluid, available, true);
        if (fits == 0) {
            return 0;
        }
        int taken = from.extract(fluid, fits, false);
        int placed = taken == 0 ? 0 : to.insert(fluid, taken, false);
        if (placed < taken) {
            // The destination took less than it said it would: give the rest back.
            int returned = from.insert(fluid, taken - placed, false);
            if (returned < taken - placed) {
                throw new IllegalStateException("Could not return " + (taken - placed - returned) + " mB of " + fluid + " after a failed transfer");
            }
        }
        return placed;
    }
}
