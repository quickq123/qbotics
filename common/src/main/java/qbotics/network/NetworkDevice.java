package qbotics.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A block that has an address on a cable network: a computer, or one of the item blocks
 * (design doc §9.3 and §11.0). Implemented by block entities, and by the plates on a cable.
 */
public interface NetworkDevice {
    enum Kind {
        COMPUTER("computer"), ANTENNA("antenna"), INTERFACE("interface"),
        /** A Serial Bus: works the container it points at, taking out and putting in. */
        BUS("bus"),
        /** World plates (design doc §11.0.1): redstone and watching, placing, breaking. */
        PIN("pin"), PLACER("placer"), BREAKER("breaker"), DROPPER("dropper"),
        /** Holds hard drives, which every computer on its network sees under /net (design doc §17.6). */
        DRIVE_BAY("drive_bay"),
        /** Copies programs from its hard drive onto the computer in front of it (design doc §5.4). */
        PROGRAMMER("programmer"),
        /** Displays (design doc §10): a Text Monitor's character grid, a Control Panel's widgets. */
        MONITOR("monitor"), PANEL("panel"), WEB("web_panel"),
        /** Lets its network's computers reach the real internet (design doc §9.4). */
        UPLINK("uplink"),
        /**
         * Game interfaces: a player's own inventory (as a bus on them), the server's chat, and
         * commands at a command block's level (that one has no recipe).
         */
        PLAYER("player_interface"), CHAT("chat_interface"), COMMAND("command_interface"),
        /** Sees the entities in a box around it and acts on them as its owner (docs/reference.md §6.12). */
        ENTITY_SENSOR("entity_sensor"),
        /** A chest's worth of slots that picks up what lies around it (docs/reference.md §6.13). */
        COLLECTOR("collector"),
        /** Reads, watches and works the blocks in a small box near it (docs/reference.md §6.14). */
        BLOCK_SENSOR("block_sensor");

        /** The word Python programs and messages use. */
        public final String word;

        Kind(String word) {
            this.word = word;
        }

        /** True for the kinds that are plates on a cable. */
        public boolean isPlate() {
            return this == BUS || this == PIN || this == PLACER || this == BREAKER || this == DROPPER;
        }

        /** True for the plates that move things between containers, in each medium. */
        public boolean isLogistics() {
            return this == BUS;
        }

        /** The plate kind with this word, or null if there is none. */
        public static @org.jspecify.annotations.Nullable Kind plate(String word) {
            if (word.equals("input") || word.equals("output")) {
                return BUS; // saved before Input and Output were folded into the Serial Bus (2026-11-12)
            }
            for (Kind kind : values()) {
                if (kind.isPlate() && kind.word.equals(word)) {
                    return kind;
                }
            }
            return null;
        }
    }

    /** Identifies this placed block. Its hardware (MAC) address is made from it. */
    UUID deviceId();

    Kind kind();

    /** What it carries. Computers and antennas carry nothing; they answer ITEM. */
    default Medium medium() {
        return Medium.ITEM;
    }

    /** The word programs and messages use for it: "bus", "fluid_bus", "usb", "computer". */
    default String kindWord() {
        return medium().prefix + kind().word;
    }

    /** The name its owner gave it, or "" if it has none. */
    String deviceName();

    void setDeviceName(String name);

    /** The last number of its internal IP address (192.168.1.N); 0 until a network has given it one. */
    int hostNumber();

    void setHostNumber(int number);

    BlockPos getBlockPos();

    boolean isRemoved();

    /** False for a side that doesn't join the network: the side a bus or output points at its target. */
    default boolean connectsOn(Direction side) {
        return true;
    }

    /**
     * False for a block that joins the network but isn't a device on it: a part of a screen
     * of several blocks, whose top-left block is the device.
     */
    default boolean listed() {
        return true;
    }

    default String mac() {
        return Addresses.mac(deviceId());
    }

    default String ip() {
        return Addresses.ip(hostNumber());
    }
}
