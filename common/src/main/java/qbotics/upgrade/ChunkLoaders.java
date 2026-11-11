package qbotics.upgrade;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import qbotics.Qbotics;
import qbotics.QboticsConfig;
import qbotics.block.AntennaBlockEntity;
import qbotics.network.CableNetwork;
import qbotics.network.RadioNetwork;

/**
 * Chunk Loader Upgrades in antennas: each one that is switched on (and allowed by the server's
 * settings) keeps the chunks of its cable network loaded and ticking, nearest the antenna
 * first, up to {@code chunks_per_loader}. Looked at once a second, and soon after any change.
 *
 * <p>The chunks are held through the loader's tickets, which are saved with the world: a base
 * comes back loaded after a restart, and its antenna then decides again what it holds.
 */
public final class ChunkLoaders {
    /** What an antenna's chunk loading is doing. The numbers are what its screen is sent. */
    public enum Status {
        NO_UPGRADE("no_upgrade"), OFF("off"), LOADING("loading"), DISABLED("disabled"), OWNER_OFFLINE("owner_offline"),
        OVER_LIMIT("over_limit"), INACTIVE("inactive"), NO_OWNER("no_owner");

        /** The word programs see. */
        public final String word;

        Status(String word) {
            this.word = word;
        }

        public static Status of(int number) {
            return number >= 0 && number < values().length ? values()[number] : NO_UPGRADE;
        }
    }

    private record Key(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /** The chunks each antenna holds now. */
    private static final Map<Key, Set<Long>> HELD = new HashMap<>();
    /** How many looks in a row an antenna holding chunks hasn't been seen (it isn't loaded). */
    private static final Map<Key, Integer> UNSEEN = new HashMap<>();
    private static volatile boolean dirty = true;
    private static long lastLook;

    private ChunkLoaders() {
    }

    /** Something changed (an upgrade, the switch): look again soon. */
    public static void changed() {
        dirty = true;
    }

    /** At world load: the chunks an antenna's tickets held when the world was saved. */
    public static void seed(ServerLevel level, BlockPos owner, Collection<Long> chunks) {
        HELD.computeIfAbsent(new Key(level.dimension(), owner.immutable()), k -> new HashSet<>()).addAll(chunks);
    }

    /** The antenna is broken: everything it held is let go. */
    public static void release(ServerLevel level, BlockPos pos) {
        Set<Long> held = HELD.remove(new Key(level.dimension(), pos));
        if (held != null) {
            for (long chunk : held) {
                Qbotics.platform().forceChunk(level, pos, ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
            }
        }
        dirty = true;
    }

    /** The server is stopping: forget what is held (the tickets themselves are saved with the world). */
    public static void clear() {
        HELD.clear();
        UNSEEN.clear();
        dirty = true;
    }

    /** Once a tick: once a second (or sooner after a change), decides again what each antenna holds. */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        if (now - lastLook < (dirty ? 5 : 20)) {
            return;
        }
        lastLook = now;
        dirty = false;
        List<AntennaBlockEntity> antennas = RadioNetwork.get(server).loaded();
        // The same order every time, so the same loaders win when a player is over the limit.
        antennas.sort(Comparator.<AntennaBlockEntity, String>comparing(a -> a.getLevel().dimension().identifier().toString())
                .thenComparingLong(a -> a.getBlockPos().asLong()));
        Map<UUID, Integer> perOwner = new HashMap<>();
        Set<Key> seen = new HashSet<>();
        for (AntennaBlockEntity antenna : antennas) {
            if (!(antenna.getLevel() instanceof ServerLevel level)) {
                continue;
            }
            Key key = new Key(level.dimension(), antenna.getBlockPos().immutable());
            seen.add(key);
            Status status = statusOf(server, antenna, perOwner);
            Set<Long> want = new HashSet<>();
            int wanted = 0;
            if (status == Status.LOADING) {
                perOwner.merge(antenna.owner(), 1, Integer::sum);
                List<Long> chunks = new ArrayList<>(CableNetwork.at(level, antenna.getBlockPos()).chunks());
                long home = ChunkPos.pack(antenna.getBlockPos());
                if (!chunks.contains(home)) {
                    chunks.add(home);
                }
                int homeX = ChunkPos.getX(home);
                int homeZ = ChunkPos.getZ(home);
                chunks.sort(Comparator.<Long>comparingInt(c -> Math.max(Math.abs(ChunkPos.getX(c) - homeX), Math.abs(ChunkPos.getZ(c) - homeZ)))
                        .thenComparingLong(c -> c));
                wanted = chunks.size();
                want.addAll(chunks.subList(0, Math.min(chunks.size(), Math.max(1, QboticsConfig.chunksPerLoader))));
            }
            hold(level, key, want);
            antenna.setLoaderState(status.ordinal(), want.size(), wanted);
        }
        // Qbots with the upgrade hold the chunk they are in (and the next, near its edge), sharing the owner's loader count.
        for (qbotics.qbot.QbotBlockEntity qbot : qbotics.qbot.QbotBlockEntity.loaded()) {
            if (!(qbot.getLevel() instanceof ServerLevel level)) {
                continue;
            }
            Key key = new Key(level.dimension(), qbot.getBlockPos().immutable());
            seen.add(key);
            boolean loads = qbot.hasUpgrade(UpgradeItem.Kind.CHUNK_LOADER) && !qbot.bricked() && QboticsConfig.chunkLoadingEnabled
                    && qbot.owner() != null && (QboticsConfig.chunkLoadingWhileOffline || server.getPlayerList().getPlayer(qbot.owner()) != null);
            if (loads) {
                int limit = QboticsConfig.chunkLoadersPerPlayer;
                loads = limit < 0 || perOwner.getOrDefault(qbot.owner(), 0) < limit || qbotics.limits.Limits.exempt(server, qbot.owner());
            }
            if (loads) {
                perOwner.merge(qbot.owner(), 1, Integer::sum);
            }
            hold(level, key, loads ? chunksAround(qbot.getBlockPos()) : Set.of());
        }
        // An antenna holding chunks that hasn't been seen for a while is gone: let its chunks go.
        for (Key key : new ArrayList<>(HELD.keySet())) {
            if (seen.contains(key)) {
                UNSEEN.remove(key);
            } else if (UNSEEN.merge(key, 1, Integer::sum) >= 3) {
                ServerLevel level = server.getLevel(key.dimension());
                if (level != null) {
                    release(level, key.pos());
                } else {
                    HELD.remove(key);
                }
                UNSEEN.remove(key);
            }
        }
    }

    private static Status statusOf(MinecraftServer server, AntennaBlockEntity antenna, Map<UUID, Integer> perOwner) {
        if (!antenna.hasUpgrade(UpgradeItem.Kind.CHUNK_LOADER)) {
            return Status.NO_UPGRADE;
        }
        if (antenna.bricked()) {
            return Status.INACTIVE;
        }
        if (!antenna.chunkLoading()) {
            return Status.OFF;
        }
        if (!QboticsConfig.chunkLoadingEnabled) {
            return Status.DISABLED;
        }
        UUID owner = antenna.owner();
        if (owner == null) {
            return Status.NO_OWNER;
        }
        if (!QboticsConfig.chunkLoadingWhileOffline && server.getPlayerList().getPlayer(owner) == null) {
            return Status.OWNER_OFFLINE;
        }
        int limit = QboticsConfig.chunkLoadersPerPlayer;
        if (limit >= 0 && perOwner.getOrDefault(owner, 0) >= limit && !qbotics.limits.Limits.exempt(server, owner)) {
            return Status.OVER_LIMIT;
        }
        return Status.LOADING;
    }

    /** Makes an antenna hold exactly these chunks. */
    private static void hold(ServerLevel level, Key key, Set<Long> want) {
        Set<Long> held = HELD.getOrDefault(key, Set.of());
        if (held.equals(want)) {
            return;
        }
        for (long chunk : held) {
            if (!want.contains(chunk)) {
                Qbotics.platform().forceChunk(level, key.pos(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk), false);
            }
        }
        for (long chunk : want) {
            if (!held.contains(chunk)) {
                Qbotics.platform().forceChunk(level, key.pos(), ChunkPos.getX(chunk), ChunkPos.getZ(chunk), true);
            }
        }
        if (want.isEmpty()) {
            HELD.remove(key);
        } else {
            HELD.put(key, new HashSet<>(want));
        }
    }

    /**
     * The chunks a Qbot at a place holds: its own, and the next one over when it is at a
     * chunk's edge, so what it senses on every side is loaded (design doc §8.5).
     */
    public static Set<Long> chunksAround(BlockPos pos) {
        Set<Long> chunks = new HashSet<>();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int lx = pos.getX() & 15;
        int lz = pos.getZ() & 15;
        int dx = lx == 0 ? -1 : lx == 15 ? 1 : 0;
        int dz = lz == 0 ? -1 : lz == 15 ? 1 : 0;
        chunks.add(ChunkPos.pack(cx, cz));
        chunks.add(ChunkPos.pack(cx + dx, cz));
        chunks.add(ChunkPos.pack(cx, cz + dz));
        chunks.add(ChunkPos.pack(cx + dx, cz + dz));
        return chunks;
    }

    /** A Qbot stepped: what it held goes with it, held at its new place before it is let go at the old. */
    public static void moved(ServerLevel level, BlockPos from, BlockPos to) {
        Key old = new Key(level.dimension(), from.immutable());
        Set<Long> held = HELD.get(old);
        if (held == null || held.isEmpty()) {
            return;
        }
        hold(level, new Key(level.dimension(), to.immutable()), chunksAround(to));
        hold(level, old, Set.of());
    }

    /** The chunks an antenna holds now (for tests and its programs). */
    public static Set<Long> heldBy(ServerLevel level, BlockPos pos) {
        return Set.copyOf(HELD.getOrDefault(new Key(level.dimension(), pos), Set.of()));
    }
}
