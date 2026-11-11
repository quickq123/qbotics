package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.level.material.Fluid;
import org.jspecify.annotations.Nullable;

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

    /** The same as {@link #insert}, into one tank only (tanks are numbered as in {@link #tanks()}). */
    int insertInto(int tank, Fluid fluid, int max, boolean simulate);

    /** The same as {@link #extract}, out of one tank only. */
    int extractFrom(int tank, Fluid fluid, int max, boolean simulate);

    /** Moves up to an amount of a fluid from one place to another, using any tanks. */
    static int move(FluidPort from, FluidPort to, Fluid fluid, int amount) {
        return move(from, -1, to, -1, other -> other == fluid, amount);
    }

    /**
     * Moves up to an amount of fluid from one place to another and returns how much moved.
     * It is tried out before it is done, so nothing is lost or copied when the destination is
     * full or refuses the fluid.
     *
     * @param fromTank the one tank to take from, or -1 for any
     * @param toTank   the one tank to put into, or -1 for any
     * @param wanted   which fluids to move (one fluid, or a filter many pass), or null for whatever is there
     */
    static int move(FluidPort from, int fromTank, FluidPort to, int toTank, @Nullable Predicate<Fluid> wanted, int amount) {
        return move(from, fromTank, to, toTank, wanted, amount, false);
    }

    /** The same, with a trial run: with simulate true nothing changes, and the answer is how much would move. */
    static int move(FluidPort from, int fromTank, FluidPort to, int toTank, @Nullable Predicate<Fluid> wanted, int amount, boolean simulate) {
        List<Fluid> kinds = new ArrayList<>();
        List<Tank> tanks = from.tanks();
        for (int i = 0; i < tanks.size(); i++) {
            Tank tank = tanks.get(i);
            boolean moves = tank.amount() > 0 && (fromTank < 0 || i == fromTank) && (wanted == null || wanted.test(tank.fluid()));
            if (moves && !kinds.contains(tank.fluid())) {
                kinds.add(tank.fluid());
            }
        }
        int moved = 0;
        for (Fluid kind : kinds) {
            if (moved >= amount) {
                break;
            }
            int most = amount - moved;
            int available = fromTank < 0 ? from.extract(kind, most, true) : from.extractFrom(fromTank, kind, most, true);
            int fits = available == 0 ? 0 : toTank < 0 ? to.insert(kind, available, true) : to.insertInto(toTank, kind, available, true);
            if (fits == 0) {
                continue;
            }
            if (simulate) {
                moved += fits;
                continue;
            }
            int taken = fromTank < 0 ? from.extract(kind, fits, false) : from.extractFrom(fromTank, kind, fits, false);
            int placed = taken == 0 ? 0 : toTank < 0 ? to.insert(kind, taken, false) : to.insertInto(toTank, kind, taken, false);
            if (placed < taken) {
                // The destination took less than it said it would: give the rest back.
                int returned = fromTank < 0 ? from.insert(kind, taken - placed, false) : from.insertInto(fromTank, kind, taken - placed, false);
                if (returned < taken - placed) {
                    throw new IllegalStateException("Could not return " + (taken - placed - returned) + " mB of " + kind + " after a failed transfer");
                }
            }
            moved += placed;
        }
        return moved;
    }
}
