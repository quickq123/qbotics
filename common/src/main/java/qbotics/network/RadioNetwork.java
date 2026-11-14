package qbotics.network;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.block.AntennaBlockEntity;

/**
 * The server-wide radio network, the game's "internet" (design doc §9.1, §9.3): which
 * antennas are loaded, and which external address each one has. Server thread only.
 */
public final class RadioNetwork {
    /** External addresses come from 100.64.0.0/10, a range never used on the real internet. */
    private static final int MAX_EXTERNAL = (1 << 22) - 1;
    /** A device's port on its antenna is this plus the last number of its internal IP. */
    public static final int PORT_BASE = 1000;
    public static final String DEFAULT_FREQUENCY = "1";

    /** Antenna range tiers. The radius is measured on x and z only; height doesn't matter. */
    public enum Tier {
        SHORT("short", 24), LONG("long", 100), INFINITE("infinite", Integer.MAX_VALUE);

        public final String word;
        public final int radius;

        Tier(String word, int radius) {
            this.word = word;
            this.radius = radius;
        }
    }

    /**
     * Which external addresses have been given out. Saved with the world so an antenna's
     * address survives restarts and is never given to a second antenna.
     */
    public static final class ExternalAddresses extends SavedData {
        private static final Codec<ExternalAddresses> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("next").forGetter(a -> a.next),
                Codec.INT.listOf().fieldOf("freed").forGetter(a -> a.freed)).apply(instance, ExternalAddresses::new));
        // No data fixer: this data is the mod's own. NeoForge allows null here; a Fabric port must check.
        static final SavedDataType<ExternalAddresses> TYPE =
                new SavedDataType<>(Qbotics.id("radio_addresses"), ExternalAddresses::new, CODEC, null);

        private int next = 1;
        private final List<Integer> freed = new ArrayList<>();

        ExternalAddresses() {
        }

        private ExternalAddresses(int next, List<Integer> freed) {
            this.next = next;
            this.freed.addAll(freed);
        }

        int take() {
            setDirty();
            if (!freed.isEmpty()) {
                return freed.removeFirst();
            }
            if (next > MAX_EXTERNAL) {
                throw new IllegalStateException("Out of external addresses");
            }
            return next++;
        }

        void giveBack(int number) {
            if (number > 0 && number < next && !freed.contains(number)) {
                freed.add(number);
                setDirty();
            }
        }
    }

    private static final Map<MinecraftServer, RadioNetwork> NETWORKS = new WeakHashMap<>();

    private final MinecraftServer server;
    /** External address number -> the loaded antenna that has it. */
    private final Map<Integer, AntennaBlockEntity> antennas = new HashMap<>();

    private RadioNetwork(MinecraftServer server) {
        this.server = server;
    }

    public static synchronized RadioNetwork get(MinecraftServer server) {
        return NETWORKS.computeIfAbsent(server, RadioNetwork::new);
    }

    private ExternalAddresses addresses() {
        return server.getDataStorage().computeIfAbsent(ExternalAddresses.TYPE);
    }

    /**
     * An antenna was placed or its chunk loaded. Returns the external address number it has
     * from now on: its own, or a new one if it had none or another antenna holds the same
     * (which happens when a block is copied together with its data).
     */
    public int register(AntennaBlockEntity antenna, int current) {
        AntennaBlockEntity holder = antennas.get(current);
        int number = current;
        if (current <= 0 || (holder != null && holder != antenna && !holder.isRemoved())) {
            number = addresses().take();
        }
        antennas.put(number, antenna);
        return number;
    }

    /** A new external address number, for something with an antenna built in (a Qbot's radio). */
    public int takeAddress() {
        return addresses().take();
    }

    /** The antenna's chunk unloaded. It keeps its address but can't be reached. */
    public void unregister(AntennaBlockEntity antenna, int number) {
        antennas.remove(number, antenna);
    }

    /** The antenna was broken: its address can be given to another. */
    public void release(AntennaBlockEntity antenna, int number) {
        if (antennas.remove(number, antenna)) {
            addresses().giveBack(number);
        }
    }

    /** Every antenna that is loaded now. */
    public java.util.List<AntennaBlockEntity> loaded() {
        java.util.List<AntennaBlockEntity> list = new java.util.ArrayList<>();
        for (AntennaBlockEntity antenna : antennas.values()) {
            if (!antenna.isRemoved()) {
                list.add(antenna);
            }
        }
        return list;
    }

    /** The loaded antenna with this external address, or null. */
    public @Nullable AntennaBlockEntity byExternalIp(String ip) {
        for (Map.Entry<Integer, AntennaBlockEntity> entry : antennas.entrySet()) {
            if (!entry.getValue().isRemoved() && externalIp(entry.getKey()).equals(ip)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** "100.64.0.7" for address number 7. */
    public static String externalIp(int number) {
        return number <= 0 ? "" : "100." + (64 + (number >> 16)) + "." + ((number >> 8) & 0xFF) + "." + (number & 0xFF);
    }

    /**
     * Who may reach the network behind an antenna by radio (design doc §9.1). This is the
     * first gate for anything that comes in from outside: messages, listing devices, logging
     * in. A new antenna is private.
     */
    public enum Access {
        /** Only devices of the antenna's owner. */
        PRIVATE("private"),
        /** The owner's devices, and those of players on the owner's team. */
        TEAM("team"),
        /** Anyone on the same frequency and in range. */
        PUBLIC("public");

        public final String word;

        Access(String word) {
            this.word = word;
        }

        public static @Nullable Access parse(String word) {
            for (Access access : values()) {
                if (access.word.equals(word)) {
                    return access;
                }
            }
            return null;
        }
    }

    /**
     * Why a device may not come in through an antenna, or null if it may.
     *
     * @param caller the owner of the device that is asking, or null if nobody owns it
     */
    public static @Nullable String accessProblem(MinecraftServer server, AntennaBlockEntity antenna, java.util.@Nullable UUID caller) {
        if (allowed(server, antenna.access(), antenna.owner(), caller)) {
            return null;
        }
        return antenna.access() == Access.TEAM ? "that antenna is for its owner's team only"
                : "that antenna is private: only its owner's devices may use it";
    }

    /**
     * The rule behind every lock in the mod: is someone let in to a thing with this access
     * setting and this owner? The owner always is. Used for antennas and for the Experience Interface.
     */
    public static boolean allowed(MinecraftServer server, Access access, java.util.@Nullable UUID owner, java.util.@Nullable UUID caller) {
        if (access == Access.PUBLIC || java.util.Objects.equals(owner, caller)) {
            return true;
        }
        if (access == Access.TEAM && owner != null && caller != null) {
            // Teams are the game's own (the /team command); they list players by name.
            String ownerName = playerName(server, owner);
            String callerName = playerName(server, caller);
            var mine = ownerName == null ? null : server.getScoreboard().getPlayersTeam(ownerName);
            var theirs = callerName == null ? null : server.getScoreboard().getPlayersTeam(callerName);
            return mine != null && mine == theirs;
        }
        return false;
    }

    /**
     * A player's name, from who is online or from the server's own record of who has played.
     * Null if the server has never seen them. It never asks the internet, which could stall the game.
     */
    public static @Nullable String playerName(MinecraftServer server, java.util.UUID player) {
        var online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            return online.getGameProfile().name();
        }
        return server.services().nameToIdCache().get(player).map(net.minecraft.server.players.NameAndId::name).orElse(null);
    }

    /** Why two antennas can't talk, or null if they can (design doc §9.1). */
    public static @Nullable String linkProblem(AntennaBlockEntity from, AntennaBlockEntity to) {
        if (from.bricked() || to.bricked()) {
            // An inert antenna (design doc §5.3) sends and receives nothing.
            return (from.bricked() ? "this network's" : "that") + " antenna is inactive: its owner has more antennas than they may have";
        }
        if (from == to) {
            return null;
        }
        if (!from.frequency().equals(to.frequency())) {
            return "that antenna is tuned to a different frequency";
        }
        return rangeProblem(from.tier(), from.getLevel().dimension(), from.getBlockPos(),
                to.tier(), to.getLevel().dimension(), to.getBlockPos());
    }

    /**
     * In one dimension, two antennas reach each other if they are within the larger of their
     * two ranges, so a big base tower serves small antennas far away. Across dimensions both
     * must be Infinite tier.
     */
    public static @Nullable String rangeProblem(Tier fromTier, ResourceKey<Level> fromDimension, BlockPos from,
            Tier toTier, ResourceKey<Level> toDimension, BlockPos to) {
        if (!fromDimension.equals(toDimension)) {
            return fromTier == Tier.INFINITE && toTier == Tier.INFINITE ? null
                    : "that antenna is in another dimension; both antennas must be Infinite tier";
        }
        long dx = from.getX() - to.getX();
        long dz = from.getZ() - to.getZ();
        long reach = Math.max(fromTier.radius, toTier.radius);
        return dx * dx + dz * dz <= reach * reach ? null
                : "that antenna is out of range (" + (long) Math.sqrt(dx * dx + dz * dz) + " blocks away; the reach is " + reach + ")";
    }
}
