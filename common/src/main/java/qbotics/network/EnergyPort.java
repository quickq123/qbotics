package qbotics.network;

/**
 * Somewhere energy can be read, put in and taken out: an Energy Interface's own store, or
 * any machine seen through one of its faces. Each loader supplies the second kind with its
 * standard energy API. The unit is the one mods share (FE, also known as RF).
 */
public interface EnergyPort {
    long stored();

    long capacity();

    /**
     * Puts in up to max units of energy.
     *
     * @param simulate true to only work out the answer, changing nothing
     * @return how much went in
     */
    int insert(int max, boolean simulate);

    /** Takes out up to max units of energy. Returns how much came out. */
    int extract(int max, boolean simulate);

    /** Moves up to an amount of energy from one place to another and returns how much moved. */
    static int move(EnergyPort from, EnergyPort to, int amount) {
        int available = from.extract(amount, true);
        int fits = available == 0 ? 0 : to.insert(available, true);
        if (fits == 0) {
            return 0;
        }
        int taken = from.extract(fits, false);
        int placed = taken == 0 ? 0 : to.insert(taken, false);
        if (placed < taken) {
            from.insert(taken - placed, false); // give back what the destination didn't take after all
        }
        return placed;
    }
}
