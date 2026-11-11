package qbotics.limits;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A device that counts against its owner's limit (design doc §5): a computer or an antenna.
 * Over the limit, or placed by a machine, it is bricked: inert, costing nothing, until it can
 * come back on. Implemented by the devices' block entities.
 */
public interface Limited {
    /** Which limit it counts against: {@link Limits#COMPUTER} or {@link Limits#ANTENNA}. */
    String limitType();

    UUID deviceId();

    @Nullable UUID owner();

    /** True while it is inert. */
    boolean bricked();

    /** Switches it off (or back on). The block entity saves this and updates its look. */
    void setBricked(boolean bricked);
}
