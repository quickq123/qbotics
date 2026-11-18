package qbotics.client.ide;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import qbotics.net.IdePayloads.FileRequest;

/**
 * The Git panel's simple mode (design doc §17.1): a folder of a computer's drive copied to the
 * player's PC as a .zip, and a .zip or folder from the PC copied onto the drive. What comes from
 * the PC is checked before anything is sent: each name as the drive allows names (nothing can
 * reach outside the folder it goes to), at most so many files and so many bytes (a .zip is read
 * only that far, so a small file that unpacks huge can't fill the PC's memory), links never
 * followed, and the clutter other systems add left out. It goes into a new folder, so nothing
 * on the drive is overwritten. The server checks every path and the drive's space again.
 */
final class DriveTransfer {
    private DriveTransfer() {
    }

    /** At most this many files, and this many bytes in all, come from the PC (a Server's drive is 2 MB). */
    static final int MOST_FILES = 400;
    static final long MOST_BYTES = 2L * 1024 * 1024;
    /** The deepest a path may go below the folder it is put in (the drive allows 16 in all). */
    static final int MOST_DEPTH = 12;
    /** The most one message carries: bigger files go in parts. */
    private static final int PART = qbotics.net.IdePayloads.MAX_FILE_BYTES;
    private static final String ALLOWED_PUNCTUATION = " ._-+()[]{}@#$%&!',=~^";
    private static final Set<String> WINDOWS_RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5",
            "com6", "com7", "com8", "com9", "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    /** What other systems and tools leave in folders: not the player's work. */
    private static final Set<String> CLUTTER = Set.of("__macosx", ".ds_store", "thumbs.db", "desktop.ini", ".git", ".gitqbot", "__pycache__");

    /** What a .zip or folder from the PC would put on the drive, and what was left out and why. */
    record Plan(String name, Map<String, byte[]> files, List<String> skipped, long bytes) {
    }

    /** Whether the drive allows this name for a file or folder (as DriveFileSystem.checkName). */
    static boolean allowedName(String name) {
        if (name.isEmpty() || name.length() > 64 || name.startsWith(" ") || name.endsWith(" ") || name.endsWith(".") || name.startsWith(".~")
                || name.equals(".") || name.equals("..")) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(Character.isLetterOrDigit(c) || ALLOWED_PUNCTUATION.indexOf(c) >= 0)) {
                return false;
            }
        }
        int dot = name.indexOf('.');
        return !WINDOWS_RESERVED.contains((dot < 0 ? name : name.substring(0, dot)).toLowerCase(Locale.ROOT));
    }

    /**
     * A path from a .zip or folder made safe, as the parts below the new folder; or null, with
     * why in reasons, if it can't be. Never absolute, never "..", never a drive letter.
     */
    static @Nullable List<String> safeParts(String raw, List<String> reasons) {
        String path = raw.replace('\\', '/');
        if (path.startsWith("/") || (path.length() > 1 && path.charAt(1) == ':')) {
            reasons.add(raw + ": a path from the top of a disk");
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                reasons.add(raw + ": reaches outside its folder");
                return null;
            }
            if (CLUTTER.contains(part.toLowerCase(Locale.ROOT))) {
                return null; // left out quietly
            }
            if (!allowedName(part)) {
                reasons.add(raw + ": \"" + part + "\" isn't a name the drive allows");
                return null;
            }
            parts.add(part);
        }
        if (parts.isEmpty()) {
            return null;
        }
        if (parts.size() > MOST_DEPTH) {
            reasons.add(raw + ": folders too deep");
            return null;
        }
        return parts;
    }

    /** A name for the new folder from a file or folder name on the PC, made one the drive allows. */
    static String folderName(String from) {
        String name = from.replaceAll("(?i)\\.zip$", "");
        StringBuilder out = new StringBuilder();
        for (char c : name.toCharArray()) {
            out.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
        }
        String made = out.toString().replaceAll("^_+|_+$", "");
        if (made.length() > 40) {
            made = made.substring(0, 40);
        }
        return made.isEmpty() || !allowedName(made) ? "from_pc" : made;
    }

    /** The files of a .zip, read only as far as the limits allow. */
    static Plan fromZip(Path zip) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (entry.isDirectory()) {
                    continue;
                }
                List<String> parts = safeParts(entry.getName(), skipped);
                if (parts == null) {
                    continue;
                }
                if (files.size() >= MOST_FILES) {
                    skipped.add("more than " + MOST_FILES + " files: the rest were left out");
                    break;
                }
                byte[] content = readAtMost(in, MOST_BYTES - total + 1);
                if (total + content.length > MOST_BYTES) {
                    skipped.add("more than " + MOST_BYTES / 1024 + " KB in all: the rest were left out");
                    break;
                }
                total += content.length;
                files.put(String.join("/", parts), content);
            }
        }
        return new Plan(folderName(zip.getFileName().toString()), withoutOneTopFolder(files), skipped, total);
    }

    /** The files of a folder on the PC: links aren't followed, and only so much is read. */
    static Plan fromFolder(Path folder) throws IOException {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        long[] total = {0};
        walk(folder, folder, files, skipped, total, 0);
        return new Plan(folderName(folder.getFileName() == null ? "from_pc" : folder.getFileName().toString()), files, skipped, total[0]);
    }

    private static void walk(Path root, Path dir, Map<String, byte[]> files, List<String> skipped, long[] total, int depth) throws IOException {
        if (depth > MOST_DEPTH) {
            skipped.add(root.relativize(dir) + ": folders too deep");
            return;
        }
        List<Path> children;
        try (var listing = Files.list(dir)) {
            children = listing.sorted().toList();
        }
        for (Path child : children) {
            if (files.size() >= MOST_FILES || total[0] > MOST_BYTES) {
                skipped.add("more than " + MOST_FILES + " files or " + MOST_BYTES / 1024 + " KB: the rest were left out");
                return;
            }
            String relative = root.relativize(child).toString().replace('\\', '/');
            if (Files.isSymbolicLink(child)) {
                skipped.add(relative + ": a link (not followed)");
                continue;
            }
            List<String> parts = safeParts(relative, skipped);
            if (parts == null) {
                continue;
            }
            if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                walk(root, child, files, skipped, total, depth + 1);
            } else if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                long size = Files.size(child);
                if (total[0] + size > MOST_BYTES) {
                    skipped.add("more than " + MOST_BYTES / 1024 + " KB in all: the rest were left out");
                    return;
                }
                total[0] += size;
                files.put(String.join("/", parts), Files.readAllBytes(child));
            }
        }
    }

    private static byte[] readAtMost(InputStream in, long most) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long read = 0;
        for (int n; (n = in.read(buffer)) > 0 && read <= most; ) {
            out.write(buffer, 0, n);
            read += n;
        }
        return out.toByteArray();
    }

    /** A .zip made of one folder holds everything under that folder's name: that level is dropped. */
    private static Map<String, byte[]> withoutOneTopFolder(Map<String, byte[]> files) {
        String top = null;
        for (String path : files.keySet()) {
            int slash = path.indexOf('/');
            String first = slash < 0 ? null : path.substring(0, slash);
            if (first == null || (top != null && !top.equals(first))) {
                return files;
            }
            top = first;
        }
        if (top == null) {
            return files;
        }
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : files.entrySet()) {
            out.put(e.getKey().substring(top.length() + 1), e.getValue());
        }
        return out;
    }

    // ---- to and from the drive -------------------------------------------------------

    /** Every file below a folder of the drive, read whole (in parts), then written as a .zip. */
    static void saveZip(IdeBackend backend, String folder, Path to, Consumer<String> done) {
        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> folders = new ArrayList<>(List.of(folder));
        collect(backend, folders, files, () -> {
            try (OutputStream file = Files.newOutputStream(to); ZipOutputStream zip = new ZipOutputStream(file)) {
                String top = folder.equals("/") ? "drive" : folder.substring(folder.lastIndexOf('/') + 1);
                for (Map.Entry<String, byte[]> e : files.entrySet()) {
                    String inside = folder.equals("/") ? e.getKey().substring(1) : e.getKey().substring(folder.length() + 1);
                    zip.putNextEntry(new ZipEntry(top + "/" + inside));
                    zip.write(e.getValue());
                    zip.closeEntry();
                }
                done.accept("Saved " + files.size() + " files to " + to);
            } catch (IOException e) {
                done.accept("!Couldn't write " + to + ": " + e.getMessage());
            }
        }, error -> done.accept("!" + error));
    }

    /** Walks the folders still to list, reading each file found, then runs then. */
    private static void collect(IdeBackend backend, List<String> folders, Map<String, byte[]> files, Runnable then, Consumer<String> failed) {
        if (folders.isEmpty()) {
            then.run();
            return;
        }
        String folder = folders.removeFirst();
        backend.file(FileRequest.LIST, folder, new byte[0], (errno, data) -> {
            if (errno != 0) {
                failed.accept("Couldn't list " + folder + " (error " + errno + ")");
                return;
            }
            List<String> toRead = new ArrayList<>();
            for (String entry : new String(data, StandardCharsets.UTF_8).split("\n")) {
                String[] fields = entry.split("\t");
                if (fields.length == 3) {
                    String path = folder.equals("/") ? "/" + fields[2] : folder + "/" + fields[2];
                    if (fields[0].equals("d")) {
                        folders.add(path);
                    } else {
                        toRead.add(path);
                    }
                }
            }
            readAll(backend, toRead, files, () -> collect(backend, folders, files, then, failed), failed);
        });
    }

    private static void readAll(IdeBackend backend, List<String> paths, Map<String, byte[]> files, Runnable then, Consumer<String> failed) {
        if (paths.isEmpty()) {
            then.run();
            return;
        }
        String path = paths.removeFirst();
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        readFrom(backend, path, 0, content, () -> {
            files.put(path, content.toByteArray());
            readAll(backend, paths, files, then, failed);
        }, failed);
    }

    private static void readFrom(IdeBackend backend, String path, long offset, ByteArrayOutputStream into, Runnable then, Consumer<String> failed) {
        backend.file(FileRequest.READ_AT, path, java.nio.ByteBuffer.allocate(8).putLong(offset).array(), (errno, data) -> {
            if (errno != 0) {
                failed.accept("Couldn't read " + path + " (error " + errno + ")");
                return;
            }
            into.write(data, 0, data.length);
            if (data.length == PART) {
                readFrom(backend, path, offset + data.length, into, then, failed);
            } else {
                then.run();
            }
        });
    }

    /** Puts a plan's files on the drive in a new folder at its top (named after it, made unique). */
    static void load(IdeBackend backend, Plan plan, Consumer<String> done) {
        backend.file(FileRequest.LIST, "/", new byte[0], (errno, data) -> {
            Set<String> taken = new java.util.HashSet<>();
            for (String entry : new String(data, StandardCharsets.UTF_8).split("\n")) {
                String[] fields = entry.split("\t");
                if (fields.length == 3) {
                    taken.add(fields[2].toLowerCase(Locale.ROOT));
                }
            }
            String name = plan.name();
            for (int n = 2; taken.contains(name.toLowerCase(Locale.ROOT)); n++) {
                name = plan.name() + "_" + n;
            }
            String top = "/" + name;
            // Folders first, shallowest first; then the files, each in parts if it is big.
            java.util.TreeSet<String> dirs = new java.util.TreeSet<>(java.util.Comparator.comparingInt((String d) -> d.split("/").length)
                    .thenComparing(d -> d));
            dirs.add(top);
            for (String path : plan.files().keySet()) {
                String[] parts = path.split("/");
                StringBuilder dir = new StringBuilder(top);
                for (int i = 0; i < parts.length - 1; i++) {
                    dir.append('/').append(parts[i]);
                    dirs.add(dir.toString());
                }
            }
            String folder = top;
            makeDirs(backend, new ArrayList<>(dirs), () -> writeFiles(backend, folder, new ArrayList<>(plan.files().entrySet()), () ->
                    done.accept("Loaded " + plan.files().size() + " files into " + folder
                            + (plan.skipped().isEmpty() ? "." : " (" + plan.skipped().size() + " left out)")), done), done);
        });
    }

    private static void makeDirs(IdeBackend backend, List<String> dirs, Runnable then, Consumer<String> done) {
        if (dirs.isEmpty()) {
            then.run();
            return;
        }
        String dir = dirs.removeFirst();
        backend.file(FileRequest.MKDIR, dir, new byte[0], (errno, data) -> {
            if (errno != 0) {
                done.accept("!Couldn't make the folder " + dir + ": " + describe(errno));
                return;
            }
            makeDirs(backend, dirs, then, done);
        });
    }

    private static void writeFiles(IdeBackend backend, String top, List<Map.Entry<String, byte[]>> files, Runnable then, Consumer<String> done) {
        if (files.isEmpty()) {
            then.run();
            return;
        }
        Map.Entry<String, byte[]> file = files.removeFirst();
        String path = top + "/" + file.getKey();
        byte[] content = file.getValue();
        writeFrom(backend, path, content, 0, () -> writeFiles(backend, top, files, then, done), done);
    }

    private static void writeFrom(IdeBackend backend, String path, byte[] content, int offset, Runnable then, Consumer<String> done) {
        int end = Math.min(content.length, offset + PART);
        byte[] part = java.util.Arrays.copyOfRange(content, offset, end);
        backend.file(offset == 0 ? FileRequest.WRITE : FileRequest.APPEND, path, part, (errno, data) -> {
            if (errno != 0) {
                done.accept("!Stopped at " + path + ": " + describe(errno) + ". What was copied before it stays.");
                return;
            }
            if (end < content.length) {
                writeFrom(backend, path, content, end, then, done);
            } else {
                then.run();
            }
        });
    }

    private static String describe(int errno) {
        return errno == qbotics.engine.HostFileSystem.ENOSPC || errno == qbotics.engine.HostFileSystem.EFBIG ? "the drive is full"
                : errno == qbotics.engine.HostFileSystem.EINVAL ? "a name the drive doesn't allow"
                : errno == qbotics.engine.HostFileSystem.EACCES ? "not allowed" : "error " + errno;
    }
}
