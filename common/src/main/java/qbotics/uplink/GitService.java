package qbotics.uplink;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsConfig;
import qbotics.engine.HostFileSystem;

/**
 * git for in-game computers (design doc §17.5), through the uplink: clone and pull a GitHub
 * repository into a folder on the computer's drive, see what changed (status), and push those
 * changes back as a commit, with the player's own token. No git program is needed: a clone is
 * the repository's archive at one commit; a push uses GitHub's API to make a commit on top of
 * that one (and refuses if the branch moved since: pull first).
 *
 * <p>A cloned folder keeps what it came from in a file, {@code .gitqbot}: the repository, the
 * branch, the commit, and a fingerprint of every file as it was, so status can tell what
 * changed. Runs on the uplink's threads; reads and writes the computer's drive directly.
 */
public final class GitService {
    private GitService() {
    }

    static final String META = ".gitqbot";
    private static final String API = "https://api.github.com";
    private static final int MAX_FILES = 500;
    private static final Pattern GITHUB = Pattern.compile(
            "(?:https://)?(?:www\\.)?github\\.com/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+?)(?:\\.git)?(?:/tree/([^/?#]+))?/?");

    private static final class GitError extends Exception {
        GitError(String message) {
            super(message, null, false, false);
        }
    }

    /** A git call from a computer: it needs an Uplink on its network, as requests do. On the server thread. */
    public static void fromComputer(MinecraftServer server, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
            net.minecraft.core.BlockPos computerPos, HostFileSystem drive, String call, CompletableFuture<String> answer) {
        net.minecraft.server.level.ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.isLoaded(computerPos)) {
            answer.complete(Uplink.error("This computer's block isn't loaded."));
            return;
        }
        if (!Uplink.hasUplink(level, computerPos)) {
            answer.complete(Uplink.error("git needs an Uplink on this computer's cable network."));
            return;
        }
        UUID owner = level.getBlockEntity(computerPos) instanceof qbotics.block.ComputerBlockEntity computer ? computer.owner() : null;
        run(server, owner, "computer at " + computerPos.toShortString(), drive, call, answer);
    }

    /**
     * A git call from a program: ["git", op, dir (absolute), url or message or null, force].
     * The checks that need the world are done by the caller; this runs the rest on the uplink.
     */
    public static void run(MinecraftServer server, @Nullable UUID owner, String from, HostFileSystem drive, String call,
            CompletableFuture<String> answer) {
        if (!QboticsConfig.gitEnabled) {
            answer.complete(Uplink.error("git is switched off on this server."));
            return;
        }
        Uplink.go(owner, from, answer, () -> {
            try {
                JsonArray args = JsonParser.parseString(call).getAsJsonArray();
                String op = args.get(1).getAsString();
                String dir = args.get(2).getAsString();
                String extra = args.size() > 3 && !args.get(3).isJsonNull() ? args.get(3).getAsString() : null;
                boolean force = args.size() > 4 && args.get(4).getAsBoolean();
                JsonElement result = switch (op) {
                    case "clone" -> clone(server, owner, from, drive, extra, dir);
                    case "pull" -> pull(server, owner, from, drive, dir, force);
                    case "status" -> status(drive, dir);
                    case "push" -> push(server, owner, from, drive, dir, extra);
                    default -> throw new GitError("git can clone, pull, status and push.");
                };
                JsonObject reply = new JsonObject();
                reply.add("r", result);
                return reply.toString();
            } catch (GitError e) {
                return Uplink.error(e.getMessage());
            } catch (Uplink.UplinkError e) {
                return Uplink.error(e.getMessage());
            } catch (IOException e) {
                return Uplink.error("git failed: " + e.getMessage());
            } catch (HostFileSystem.FsException e) {
                return Uplink.error(e.errno == HostFileSystem.ENOSPC || e.errno == HostFileSystem.EFBIG
                        ? "The computer's drive is full." : "The computer's drive couldn't be written (error " + e.errno + ").");
            }
        });
    }

    // ---- GitHub ---------------------------------------------------------------------------

    private static Map<String, String> headers(MinecraftServer server, @Nullable UUID owner) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        String token = owner == null ? null : GitTokens.of(server, owner);
        if (token != null) {
            headers.put("Authorization", "Bearer " + token); // read here only; never shown to programs
        }
        return headers;
    }

    private static JsonElement api(MinecraftServer server, @Nullable UUID owner, String from, String method, String path,
            @Nullable JsonObject body) throws GitError, Uplink.UplinkError, IOException {
        Map<String, String> headers = headers(server, owner);
        byte[] bytes = null;
        if (body != null) {
            headers.put("Content-Type", "application/json");
            bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        }
        Uplink.Reply reply = Uplink.download(owner, from, method, API + path, headers, bytes, 2 * 1024 * 1024);
        String text = new String(reply.body(), StandardCharsets.UTF_8);
        if (reply.status() >= 400) {
            String message = "";
            try {
                message = JsonParser.parseString(text).getAsJsonObject().get("message").getAsString();
            } catch (RuntimeException ignored) {
                // not JSON
            }
            throw new GitError(switch (reply.status()) {
                case 401 -> "GitHub didn't accept your token. Set a new one: /qbotics git token";
                case 403 -> "GitHub refused: " + message + (message.contains("rate limit") ? " (a token raises the limit)" : "");
                case 404 -> "GitHub has no such repository or branch (or it's private, and needs your token).";
                case 409, 422 -> "GitHub refused the change: " + message;
                default -> "GitHub answered " + reply.status() + ": " + message;
            });
        }
        return text.isEmpty() ? JsonNull.INSTANCE : JsonParser.parseString(text);
    }

    private record Repo(String owner, String name, @Nullable String branch) {
    }

    private static Repo parse(String url) throws GitError {
        Matcher m = GITHUB.matcher(url.strip());
        if (!m.matches()) {
            throw new GitError("git clones GitHub repositories, such as https://github.com/owner/repo");
        }
        return new Repo(m.group(1), m.group(2), m.group(3));
    }

    // ---- the drive ---------------------------------------------------------------------------

    private static String join(String dir, String path) {
        return (dir.endsWith("/") ? dir : dir + "/") + path;
    }

    private static boolean exists(HostFileSystem drive, String path) {
        try {
            drive.call(HostFileSystem.STAT, path, new byte[0]);
            return true;
        } catch (HostFileSystem.FsException e) {
            return false;
        }
    }

    private static void mkdirs(HostFileSystem drive, String path) throws HostFileSystem.FsException {
        StringBuilder at = new StringBuilder();
        for (String part : path.split("/")) {
            if (part.isEmpty()) {
                continue;
            }
            at.append('/').append(part);
            try {
                drive.call(HostFileSystem.MKDIR, at.toString(), new byte[0]);
            } catch (HostFileSystem.FsException e) {
                if (e.errno != HostFileSystem.EEXIST) {
                    throw e;
                }
            }
        }
    }

    /** Every file under a folder, by path relative to it, with its contents (the .gitqbot file left out). */
    private static Map<String, byte[]> files(HostFileSystem drive, String dir) throws HostFileSystem.FsException {
        Map<String, byte[]> out = new TreeMap<>();
        walk(drive, dir, "", out);
        out.remove(META);
        return out;
    }

    private static void walk(HostFileSystem drive, String dir, String prefix, Map<String, byte[]> out) throws HostFileSystem.FsException {
        String listing = new String(drive.call(HostFileSystem.LIST, prefix.isEmpty() ? dir : join(dir, prefix), new byte[0]), StandardCharsets.UTF_8);
        for (String line : listing.split("\n")) {
            String[] fields = line.split("\t");
            if (fields.length != 3) {
                continue;
            }
            String rel = prefix.isEmpty() ? fields[2] : prefix + "/" + fields[2];
            if (fields[0].equals("d")) {
                walk(drive, dir, rel, out);
            } else {
                out.put(rel, drive.call(HostFileSystem.READ, join(dir, rel), new byte[0]));
            }
        }
    }

    private static String fingerprint(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(content));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonObject readMeta(HostFileSystem drive, String dir) throws GitError {
        try {
            return JsonParser.parseString(new String(drive.call(HostFileSystem.READ, join(dir, META), new byte[0]), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (HostFileSystem.FsException | RuntimeException e) {
            throw new GitError(dir + " isn't a cloned repository (git clone one first).");
        }
    }

    private static void writeMeta(HostFileSystem drive, String dir, Repo repo, String branch, String commit, Map<String, byte[]> files)
            throws HostFileSystem.FsException {
        JsonObject meta = new JsonObject();
        meta.addProperty("owner", repo.owner());
        meta.addProperty("repo", repo.name());
        meta.addProperty("branch", branch);
        meta.addProperty("commit", commit);
        JsonObject prints = new JsonObject();
        files.forEach((path, content) -> prints.addProperty(path, fingerprint(content)));
        meta.add("files", prints);
        drive.call(HostFileSystem.WRITE, join(dir, META), meta.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---- fetching a commit --------------------------------------------------------------------

    private static String branchOf(MinecraftServer server, @Nullable UUID owner, String from, Repo repo)
            throws GitError, Uplink.UplinkError, IOException {
        if (repo.branch() != null) {
            return repo.branch();
        }
        return api(server, owner, from, "GET", "/repos/" + repo.owner() + "/" + repo.name(), null).getAsJsonObject()
                .get("default_branch").getAsString();
    }

    private static String headOf(MinecraftServer server, @Nullable UUID owner, String from, Repo repo, String branch)
            throws GitError, Uplink.UplinkError, IOException {
        return api(server, owner, from, "GET", "/repos/" + repo.owner() + "/" + repo.name() + "/git/ref/heads/" + branch, null)
                .getAsJsonObject().getAsJsonObject("object").get("sha").getAsString();
    }

    /** The repository's files at one commit, from its archive, by path. */
    private static Map<String, byte[]> archive(MinecraftServer server, @Nullable UUID owner, String from, Repo repo, String commit)
            throws GitError, Uplink.UplinkError, IOException {
        int limit = QboticsConfig.gitMaxRepoKb * 1024;
        Uplink.Reply zip = Uplink.download(owner, from, "GET", API + "/repos/" + repo.owner() + "/" + repo.name() + "/zipball/" + commit,
                headers(server, owner), null, limit);
        if (zip.status() >= 400) {
            throw new GitError("GitHub wouldn't give the repository's files (" + zip.status() + ").");
        }
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip.body()))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = entry.getName();
                int slash = name.indexOf('/');
                String path = slash < 0 ? "" : name.substring(slash + 1); // the archive puts everything in one top folder
                if (entry.isDirectory() || path.isEmpty() || path.contains("..") || path.startsWith("/")) {
                    continue;
                }
                byte[] content = in.readNBytes(limit + 1);
                total += content.length;
                if (total > limit) {
                    throw new GitError("The repository is larger than a computer takes (" + QboticsConfig.gitMaxRepoKb + " KB).");
                }
                if (files.size() >= MAX_FILES) {
                    throw new GitError("The repository has more than " + MAX_FILES + " files.");
                }
                files.put(path, content);
            }
        }
        return files;
    }

    private static void writeAll(HostFileSystem drive, String dir, Map<String, byte[]> files) throws HostFileSystem.FsException {
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            String path = join(dir, file.getKey());
            int slash = path.lastIndexOf('/');
            if (slash > 0) {
                mkdirs(drive, path.substring(0, slash));
            }
            drive.call(HostFileSystem.WRITE, path, file.getValue());
        }
    }

    // ---- the commands ----------------------------------------------------------------------------

    private static JsonElement clone(MinecraftServer server, @Nullable UUID owner, String from, HostFileSystem drive, @Nullable String url,
            String dir) throws GitError, Uplink.UplinkError, IOException, HostFileSystem.FsException {
        if (url == null) {
            throw new GitError("git clone <url> [folder]");
        }
        Repo repo = parse(url);
        if (exists(drive, dir)) {
            String listing = new String(drive.call(HostFileSystem.LIST, dir, new byte[0]), StandardCharsets.UTF_8);
            if (!listing.isBlank()) {
                throw new GitError(dir + " is already there and not empty.");
            }
        }
        String branch = branchOf(server, owner, from, repo);
        String commit = headOf(server, owner, from, repo, branch);
        Map<String, byte[]> files = archive(server, owner, from, repo, commit);
        mkdirs(drive, dir);
        writeAll(drive, dir, files);
        writeMeta(drive, dir, repo, branch, commit, files);
        JsonObject out = new JsonObject();
        out.addProperty("files", files.size());
        out.addProperty("branch", branch);
        out.addProperty("commit", commit.substring(0, 7));
        out.addProperty("repo", repo.owner() + "/" + repo.name());
        return out;
    }

    /** What changed since the last clone, pull or push: {modified, added, deleted}. */
    private static JsonObject changes(HostFileSystem drive, String dir, JsonObject meta) throws HostFileSystem.FsException {
        JsonObject known = meta.getAsJsonObject("files");
        Map<String, byte[]> now = files(drive, dir);
        JsonArray modified = new JsonArray();
        JsonArray added = new JsonArray();
        JsonArray deleted = new JsonArray();
        for (Map.Entry<String, byte[]> file : now.entrySet()) {
            JsonElement was = known.get(file.getKey());
            if (was == null) {
                added.add(file.getKey());
            } else if (!was.getAsString().equals(fingerprint(file.getValue()))) {
                modified.add(file.getKey());
            }
        }
        for (String path : known.keySet()) {
            if (!now.containsKey(path)) {
                deleted.add(path);
            }
        }
        JsonObject out = new JsonObject();
        out.add("modified", modified);
        out.add("added", added);
        out.add("deleted", deleted);
        return out;
    }

    private static boolean any(JsonObject changes) {
        return !changes.getAsJsonArray("modified").isEmpty() || !changes.getAsJsonArray("added").isEmpty()
                || !changes.getAsJsonArray("deleted").isEmpty();
    }

    private static JsonElement status(HostFileSystem drive, String dir) throws GitError, HostFileSystem.FsException {
        JsonObject meta = readMeta(drive, dir);
        JsonObject out = changes(drive, dir, meta);
        out.addProperty("repo", meta.get("owner").getAsString() + "/" + meta.get("repo").getAsString());
        out.addProperty("branch", meta.get("branch").getAsString());
        out.addProperty("commit", meta.get("commit").getAsString().substring(0, 7));
        return out;
    }

    private static JsonElement pull(MinecraftServer server, @Nullable UUID owner, String from, HostFileSystem drive, String dir, boolean force)
            throws GitError, Uplink.UplinkError, IOException, HostFileSystem.FsException {
        JsonObject meta = readMeta(drive, dir);
        Repo repo = new Repo(meta.get("owner").getAsString(), meta.get("repo").getAsString(), meta.get("branch").getAsString());
        JsonObject changed = changes(drive, dir, meta);
        if (any(changed) && !force) {
            throw new GitError("You have changes that pulling would overwrite (git status shows them). Push them, or pull with --force to drop them.");
        }
        String commit = headOf(server, owner, from, repo, repo.branch());
        JsonObject out = new JsonObject();
        out.addProperty("commit", commit.substring(0, 7));
        if (commit.equals(meta.get("commit").getAsString()) && !any(changed)) {
            out.addProperty("files", 0);
            out.addProperty("already", true);
            return out;
        }
        Map<String, byte[]> files = archive(server, owner, from, repo, commit);
        // Files the old commit had and the new one doesn't go too.
        for (String path : meta.getAsJsonObject("files").keySet()) {
            if (!files.containsKey(path) && exists(drive, join(dir, path))) {
                drive.call(HostFileSystem.REMOVE, join(dir, path), new byte[0]);
            }
        }
        writeAll(drive, dir, files);
        writeMeta(drive, dir, repo, repo.branch(), commit, files);
        out.addProperty("files", files.size());
        out.addProperty("already", false);
        return out;
    }

    private static JsonElement push(MinecraftServer server, @Nullable UUID owner, String from, HostFileSystem drive, String dir,
            @Nullable String message) throws GitError, Uplink.UplinkError, IOException, HostFileSystem.FsException {
        if (!QboticsConfig.gitAllowPush) {
            throw new GitError("Pushing is switched off on this server (git.allow_push). Push from your PC instead, over the SSH gateway.");
        }
        if (owner == null || GitTokens.of(server, owner) == null) {
            throw new GitError("Pushing needs your GitHub token: set it with /qbotics git token (it stays on the server, never in programs).");
        }
        if (message == null || message.isBlank()) {
            throw new GitError("A push needs a message: git push -m \"what changed\"");
        }
        JsonObject meta = readMeta(drive, dir);
        Repo repo = new Repo(meta.get("owner").getAsString(), meta.get("repo").getAsString(), meta.get("branch").getAsString());
        String base = meta.get("commit").getAsString();
        JsonObject changed = changes(drive, dir, meta);
        if (!any(changed)) {
            throw new GitError("Nothing to push: no file has changed since the last pull.");
        }
        String path = "/repos/" + repo.owner() + "/" + repo.name();
        String head = headOf(server, owner, from, repo, repo.branch());
        if (!head.equals(base)) {
            throw new GitError("The repository has new commits since your last pull. Pull first (your changes would be lost: copy them aside), then push.");
        }
        String baseTree = api(server, owner, from, "GET", path + "/git/commits/" + base, null).getAsJsonObject()
                .getAsJsonObject("tree").get("sha").getAsString();
        Map<String, byte[]> now = files(drive, dir);
        JsonArray tree = new JsonArray();
        List<String> upload = new ArrayList<>();
        changed.getAsJsonArray("modified").forEach(p -> upload.add(p.getAsString()));
        changed.getAsJsonArray("added").forEach(p -> upload.add(p.getAsString()));
        for (String file : upload) {
            JsonObject blob = new JsonObject();
            blob.addProperty("content", Base64.getEncoder().encodeToString(now.get(file)));
            blob.addProperty("encoding", "base64");
            String sha = api(server, owner, from, "POST", path + "/git/blobs", blob).getAsJsonObject().get("sha").getAsString();
            JsonObject entry = new JsonObject();
            entry.addProperty("path", file);
            entry.addProperty("mode", "100644");
            entry.addProperty("type", "blob");
            entry.addProperty("sha", sha);
            tree.add(entry);
        }
        for (JsonElement gone : changed.getAsJsonArray("deleted")) {
            JsonObject entry = new JsonObject();
            entry.addProperty("path", gone.getAsString());
            entry.addProperty("mode", "100644");
            entry.addProperty("type", "blob");
            entry.add("sha", JsonNull.INSTANCE); // removed from the tree
            tree.add(entry);
        }
        JsonObject newTree = new JsonObject();
        newTree.addProperty("base_tree", baseTree);
        newTree.add("tree", tree);
        String treeSha = api(server, owner, from, "POST", path + "/git/trees", newTree).getAsJsonObject().get("sha").getAsString();
        JsonObject commit = new JsonObject();
        commit.addProperty("message", message);
        commit.addProperty("tree", treeSha);
        JsonArray parents = new JsonArray();
        parents.add(base);
        commit.add("parents", parents);
        String commitSha = api(server, owner, from, "POST", path + "/git/commits", commit).getAsJsonObject().get("sha").getAsString();
        JsonObject ref = new JsonObject();
        ref.addProperty("sha", commitSha);
        ref.addProperty("force", false);
        api(server, owner, from, "PATCH", path + "/git/refs/heads/" + repo.branch(), ref);
        writeMeta(drive, dir, repo, repo.branch(), commitSha, now);
        JsonObject out = new JsonObject();
        out.addProperty("commit", commitSha.substring(0, 7));
        out.addProperty("files", upload.size() + changed.getAsJsonArray("deleted").size());
        out.addProperty("branch", repo.branch());
        return out;
    }
}
