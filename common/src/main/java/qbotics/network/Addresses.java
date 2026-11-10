package qbotics.network;

import java.util.Locale;
import java.util.UUID;

/** The two kinds of address a device has on a cable network (design doc §9.3). */
public final class Addresses {
    /** Every cable network is the same private network, as home networks are. */
    public static final String INTERNAL_PREFIX = "192.168.1.";
    /** 192.168.1.1 is kept for the antenna (the router). */
    public static final int GATEWAY_HOST = 1;
    public static final int FIRST_HOST = 2;
    public static final int LAST_HOST = 254;
    public static final int MAX_NAME_LENGTH = 24;

    private Addresses() {
    }

    /**
     * The hardware address: fixed for as long as the block stays placed, and the same on
     * whatever network it is cabled to. It starts with 02, which real networks reserve for
     * addresses that weren't issued by a manufacturer.
     */
    public static String mac(UUID deviceId) {
        long bits = deviceId.getMostSignificantBits() ^ deviceId.getLeastSignificantBits();
        return String.format(Locale.ROOT, "02:%02X:%02X:%02X:%02X:%02X",
                (bits >>> 32) & 0xFF, (bits >>> 24) & 0xFF, (bits >>> 16) & 0xFF, (bits >>> 8) & 0xFF, bits & 0xFF);
    }

    /** The internal IP address for a host number, or "" if the device has none yet. */
    public static String ip(int hostNumber) {
        return hostNumber == 0 ? "" : INTERNAL_PREFIX + hostNumber;
    }

    /**
     * Makes a typed name safe to use: letters, digits, "_", "-" and "." only, spaces become
     * "_", and no longer than {@link #MAX_NAME_LENGTH}. A name may not look like an address.
     */
    public static String cleanName(String typed) {
        StringBuilder name = new StringBuilder();
        for (char c : typed.strip().toCharArray()) {
            if (name.length() == MAX_NAME_LENGTH) {
                break;
            }
            if (c == ' ') {
                name.append('_');
            } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                name.append(c);
            }
        }
        String result = name.toString();
        return result.startsWith(INTERNAL_PREFIX) ? "" : result;
    }
}
