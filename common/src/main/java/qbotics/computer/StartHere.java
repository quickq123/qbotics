package qbotics.computer;

import com.mojang.serialization.Codec;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import qbotics.Qbotics;
import qbotics.engine.HostFileSystem;
import qbotics.net.IdePayloads;

/**
 * start_here.py, once for each player (decided 2026-11-18): the first time a player opens the
 * editor of a computer of their own (a computer, server or laptop; not a Qbot, and not someone
 * else's), the program is put on its drive and opened in the editor. Who has had it is saved with
 * the world. The same file is in helpme (git clone helpme) for later.
 */
public final class StartHere extends SavedData {
    public static final String PATH = "/start_here.py";

    private static final Codec<StartHere> CODEC = UUIDUtil.CODEC.listOf()
            .xmap(StartHere::new, given -> List.copyOf(given.players));
    // No data fixer: this data is the mod's own. NeoForge allows null here; a Fabric port must check.
    private static final SavedDataType<StartHere> TYPE = new SavedDataType<>(Qbotics.id("start_here"), StartHere::new, CODEC, null);

    private final Set<UUID> players = new HashSet<>();

    private StartHere() {
    }

    private StartHere(List<UUID> saved) {
        players.addAll(saved);
    }

    static StartHere get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** Whether this player has been given start_here.py. */
    public static boolean given(MinecraftServer server, UUID player) {
        return get(server).players.contains(player);
    }

    /**
     * Called as a player's editor opens on a computer: if it is theirs, shows its own files, and
     * they haven't had it yet, puts start_here.py on it (keeping a file of that name already
     * there) and opens it in the editor.
     */
    static void offer(ServerPlayer player, ServerComputer computer, boolean theirs) {
        MinecraftServer server = player.level().getServer();
        StartHere given = get(server);
        if (!theirs || given.players.contains(player.getUUID()) || !computer.editTarget().deviceId().equals(computer.deviceId())) {
            return;
        }
        try {
            try {
                computer.drive().call(HostFileSystem.STAT, PATH, new byte[0]);
            } catch (HostFileSystem.FsException missing) {
                computer.drive().call(HostFileSystem.WRITE, PATH, text());
            }
        } catch (HostFileSystem.FsException | IOException e) {
            Qbotics.LOGGER.warn("Couldn't put start_here.py on a computer for {}", player.getName().getString(), e);
            return; // tried again next time
        }
        given.players.add(player.getUUID());
        given.setDirty();
        Qbotics.platform().sendToPlayer(player, new IdePayloads.OpenFile(computer.deviceId(), PATH));
    }

    private static byte[] text() throws IOException {
        try (InputStream in = StartHere.class.getResourceAsStream("/qbotics/helpme/start_here.py")) {
            if (in == null) {
                throw new IOException("start_here.py is missing from the mod");
            }
            return in.readAllBytes();
        }
    }
}
