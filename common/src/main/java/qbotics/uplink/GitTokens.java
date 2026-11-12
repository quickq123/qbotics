package qbotics.uplink;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;

/**
 * Each player's GitHub access token, for git push (design doc §17.5). Entered in a screen in
 * the player's game (never typed into a program or chat), kept with the world in
 * data/qbotics/git_tokens.json, used only by the game itself: no program can read it, and it
 * is never sent to another player. Anyone who can read the server's files can read it, which
 * the screen says before it is saved.
 */
public final class GitTokens {
    private GitTokens() {
    }

    private static @Nullable Path file;
    private static final Map<UUID, String> TOKENS = new HashMap<>();

    private static synchronized void load(MinecraftServer server) {
        Path wanted = server.getWorldPath(LevelResource.ROOT).resolve("data/qbotics/git_tokens.json");
        if (wanted.equals(file)) {
            return;
        }
        file = wanted;
        TOKENS.clear();
        if (!Files.exists(wanted)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(wanted, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                TOKENS.put(UUID.fromString(entry.getKey()), entry.getValue().getAsString());
            }
        } catch (IOException | RuntimeException e) {
            Qbotics.LOGGER.warn("Could not read the git tokens in {}", wanted, e);
        }
    }

    private static synchronized void save() {
        if (file == null) {
            return;
        }
        JsonObject root = new JsonObject();
        TOKENS.forEach((player, token) -> root.addProperty(player.toString(), token));
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Qbotics.LOGGER.warn("Could not save the git tokens", e);
        }
    }

    /** A player's token, or null. For the game's own use only. */
    static synchronized @Nullable String of(MinecraftServer server, UUID player) {
        load(server);
        return TOKENS.get(player);
    }

    public static synchronized boolean has(MinecraftServer server, UUID player) {
        load(server);
        return TOKENS.containsKey(player);
    }

    /** Sets (or, with an empty token, removes) a player's token. */
    public static synchronized void set(MinecraftServer server, UUID player, String token) {
        load(server);
        if (token.isBlank()) {
            TOKENS.remove(player);
        } else {
            TOKENS.put(player, token.strip());
        }
        save();
    }
}
