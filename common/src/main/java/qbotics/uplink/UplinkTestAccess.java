package qbotics.uplink;

import java.net.InetAddress;

/** For the game tests: the uplink's address check, which is otherwise private to its package. */
public final class UplinkTestAccess {
    private UplinkTestAccess() {
    }

    public static boolean forbidden(InetAddress address) {
        return Uplink.forbidden(address);
    }
}
