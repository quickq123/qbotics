package qbotics.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;

/**
 * The public keys each player logs in to the SSH gateway with (design doc §9.5), kept with the
 * world in data/qbotics/ssh_keys.json, one line each, as in an authorized_keys file. Only
 * public keys are kept: they let a key in, they can't be used to log in anywhere.
 */
public final class SshKeys {
    /** Keys any one player may have. */
    public static final int MAX_PER_PLAYER = 8;

    private final Path file;
    private final Map<UUID, List<String>> keys = new LinkedHashMap<>();

    public SshKeys(Path file) {
        this.file = file;
        load();
    }

    private synchronized void load() {
        keys.clear();
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                List<String> lines = new ArrayList<>();
                for (JsonElement line : entry.getValue().getAsJsonArray()) {
                    lines.add(line.getAsString());
                }
                keys.put(UUID.fromString(entry.getKey()), lines);
            }
        } catch (IOException | RuntimeException e) {
            Qbotics.LOGGER.warn("Could not read the SSH keys in {}", file, e);
        }
    }

    private synchronized void save() {
        JsonObject root = new JsonObject();
        keys.forEach((player, lines) -> {
            JsonArray array = new JsonArray();
            lines.forEach(array::add);
            root.add(player.toString(), array);
        });
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Qbotics.LOGGER.warn("Could not save the SSH keys in {}", file, e);
        }
    }

    /** Reads one public-key line ("ssh-ed25519 AAAA... comment"); null if it isn't one. */
    static @Nullable PublicKey parse(String line) {
        try {
            AuthorizedKeyEntry entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(line.strip());
            return entry == null ? null : entry.resolvePublicKey(null, PublicKeyEntryResolver.FAILING);
        } catch (Exception e) {
            return null;
        }
    }

    /** Adds a key for a player. Returns null if it was added, or why not. */
    public synchronized @Nullable String add(UUID player, String line) {
        PublicKey key = parse(line);
        if (key == null) {
            return "That isn't a public key. It looks like: ssh-ed25519 AAAAC3Nz... you@pc (the contents of id_ed25519.pub).";
        }
        UUID owner = ownerOf(key);
        if (owner != null) {
            return owner.equals(player) ? "You have that key already." : "That key belongs to another player.";
        }
        List<String> mine = keys.computeIfAbsent(player, p -> new ArrayList<>());
        if (mine.size() >= MAX_PER_PLAYER) {
            return "You have " + MAX_PER_PLAYER + " keys already: remove one first.";
        }
        mine.add(line.strip());
        save();
        return null;
    }

    /** A player's keys, each as its type and fingerprint (and comment), for listing. */
    public synchronized List<String> describe(UUID player) {
        List<String> out = new ArrayList<>();
        for (String line : keys.getOrDefault(player, List.of())) {
            PublicKey key = parse(line);
            String[] parts = line.split("\\s+", 3);
            out.add((key == null ? "?" : KeyUtils.getFingerPrint(key)) + (parts.length > 2 ? "  " + parts[2] : ""));
        }
        return out;
    }

    /** Removes a player's key by its number in describe (from 1). Returns false if there is none. */
    public synchronized boolean remove(UUID player, int number) {
        List<String> mine = keys.get(player);
        if (mine == null || number < 1 || number > mine.size()) {
            return false;
        }
        mine.remove(number - 1);
        if (mine.isEmpty()) {
            keys.remove(player);
        }
        save();
        return true;
    }

    /** The player a key belongs to, or null. */
    public synchronized @Nullable UUID ownerOf(PublicKey key) {
        for (Map.Entry<UUID, List<String>> entry : keys.entrySet()) {
            for (String line : entry.getValue()) {
                PublicKey known = parse(line);
                if (known != null && KeyUtils.compareKeys(known, key)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }
}
