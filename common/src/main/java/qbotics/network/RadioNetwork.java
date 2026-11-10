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

    /** Why two antennas can't talk, or null if they can (design doc §9.1). */
    public static @Nullable String linkProblem(AntennaBlockEntity from, AntennaBlockEntity to) {
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
