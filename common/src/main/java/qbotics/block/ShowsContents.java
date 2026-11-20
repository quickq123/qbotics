package qbotics.block;

/**
 * A block whose sides show what it holds: the Interfaces' slots, tanks, charge and
 * experience. Players' games are sent its data when it is first seen, and again whenever
 * what it shows changes (see {@link DeviceBlockEntity#setChanged()}).
 */
public interface ShowsContents {
    /**
     * What its sides show, made on the server and compared with what was last sent. Coarse on
     * purpose (a fill level in steps, not every unit), so a store that changes every tick is
     * not sent every tick.
     */
    String look();
}
