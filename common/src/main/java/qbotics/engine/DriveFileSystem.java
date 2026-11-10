package qbotics.engine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * A computer's drive: one real folder on the server (design doc §17.2).
 *
 * <p>Programs are untrusted, so this class is strict: a path can never leave the folder,
 * names that are awkward or dangerous on any operating system are refused, links are
 * never followed, and the folder can't grow past its quota. Writes replace the file in
 * one step, so a crash can't leave half a file.
 */
public final class DriveFileSystem implements HostFileSystem {
    private static final int MAX_NAME_LENGTH = 64;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_ENTRIES = 512;
    /** Our own temporary files; hidden from listings and not creatable by programs. */
    private static final String TEMP_PREFIX = ".~";
    private static final String ALLOWED_PUNCTUATION = " ._-+()[]{}@#$%&!',=~^";
    private static final Set<String> WINDOWS_RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private static final long USAGE_MAX_AGE_NANOS = 2_000_000_000L;

    private final Path root;
    private final long quotaBytes;
    /** Remembered result of usage(); usageBytes < 0 means "measure again". */
    private long usageBytes = -1;
    private long usageEntries;
    private long usageMeasuredAt;

    /** The folder is created the first time something is written. */
    public DriveFileSystem(Path root, long quotaBytes) {
        this.root = root.toAbsolutePath().normalize();
        this.quotaBytes = quotaBytes;
    }

    @Override
    public synchronized byte[] call(int op, String path, byte[] arg) throws FsException {
        try {
            Path target = resolve(path);
            switch (op) {
                case READ:
                    return read(target);
                case WRITE:
                    write(target, arg);
                    break;
                case APPEND:
                    append(target, arg);
                    break;
                case STAT:
                    return stat(target);
                case LIST:
                    return list(target);
                case MKDIR:
                    mkdir(target);
                    break;
                case REMOVE:
                    remove(target);
                    break;
                case RENAME:
                    rename(target, resolve(new String(arg, StandardCharsets.UTF_8)));
                    break;
                default:
                    throw new FsException(EINVAL);
            }
            return new byte[0];
        } catch (IOException e) {
            throw new FsException(EIO);
        }
    }

    // ---- paths -----------------------------------------------------------------

    /** Turns a program's path into a real one inside the drive, or refuses it. */
    private Path resolve(String path) throws FsException {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                // As on a real system, the parent of the root is the root.
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                continue;
            }
            checkName(part);
            parts.add(part);
        }
        if (parts.size() > MAX_DEPTH) {
            throw new FsException(EINVAL);
        }
        Path result = root;
        for (String part : parts) {
            result = result.resolve(part);
            // A link could point outside the drive; programs can't make one, but an
            // admin might, so never pass through one.
            if (Files.isSymbolicLink(result)) {
                throw new FsException(EACCES);
            }
        }
        return result;
    }

    private static void checkName(String name) throws FsException {
        boolean ok = name.length() <= MAX_NAME_LENGTH
                && !name.startsWith(" ") && !name.endsWith(" ") && !name.endsWith(".")
                && !name.startsWith(TEMP_PREFIX);
        for (int i = 0; ok && i < name.length(); i++) {
            char c = name.charAt(i);
            ok = Character.isLetterOrDigit(c) || ALLOWED_PUNCTUATION.indexOf(c) >= 0;
        }
        if (ok) {
            int dot = name.indexOf('.');
            String stem = (dot < 0 ? name : name.substring(0, dot)).toLowerCase(java.util.Locale.ROOT);
            ok = !WINDOWS_RESERVED.contains(stem);
        }
        if (!ok) {
            throw new FsException(EINVAL);
        }
    }

    private static boolean isFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean isDirectory(Path path) {
        return Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isRoot(Path path) {
        return path.equals(root);
    }

    // ---- operations ------------------------------------------------------------

    private byte[] read(Path file) throws IOException, FsException {
        if (isDirectory(file) || isRoot(file)) {
            throw new FsException(EISDIR);
        }
        if (!isFile(file)) {
            throw new FsException(ENOENT);
        }
        return Files.readAllBytes(file);
    }

    private void write(Path file, byte[] data) throws IOException, FsException {
        if (isRoot(file) || isDirectory(file)) {
            throw new FsException(EISDIR);
        }
        Path parent = file.getParent();
        if (!isRoot(parent) && !isDirectory(parent)) {
            throw new FsException(ENOENT);
        }
        boolean exists = isFile(file);
        long[] usage = usage();
        long newTotal = usage[0] - (exists ? Files.size(file) : 0) + data.length;
        if (newTotal > quotaBytes || (!exists && usage[1] >= MAX_ENTRIES)) {
            throw new FsException(ENOSPC);
        }
        Files.createDirectories(parent);
        Path temp = parent.resolve(TEMP_PREFIX + Long.toHexString(ThreadLocalRandom.current().nextLong()));
        try {
            Files.write(temp, data);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
            usageBytes = -1;
        }
    }

    /** Adds to the end of a file, creating it if needed. Called once per write() of a streamed file. */
    private void append(Path file, byte[] data) throws IOException, FsException {
        if (isRoot(file) || isDirectory(file)) {
            throw new FsException(EISDIR);
        }
        Path parent = file.getParent();
        if (!isRoot(parent) && !isDirectory(parent)) {
            throw new FsException(ENOENT);
        }
        boolean exists = isFile(file);
        long[] usage = usage();
        if (usage[0] + data.length > quotaBytes || (!exists && usage[1] >= MAX_ENTRIES)) {
            throw new FsException(ENOSPC);
        }
        Files.createDirectories(parent);
        Files.write(file, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        // Keep the running total current so a loop of small writes doesn't rescan the drive.
        usageBytes += data.length;
        usageEntries += exists ? 0 : 1;
    }

    private byte[] stat(Path path) throws IOException, FsException {
        if (isRoot(path) || isDirectory(path)) {
            return text("d\t0\t0");
        }
        if (!isFile(path)) {
            throw new FsException(ENOENT);
        }
        long modified = Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis() / 1000;
        return text("f\t" + Files.size(path) + "\t" + modified);
    }

    private byte[] list(Path dir) throws IOException, FsException {
        if (isRoot(dir) && !Files.exists(dir)) {
            return new byte[0]; // a brand-new, still empty drive
        }
        if (!isDirectory(dir)) {
            throw new FsException(isFile(dir) ? ENOTDIR : ENOENT);
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> entries = Files.list(dir)) {
            for (Path entry : entries.sorted().toList()) {
                String name = entry.getFileName().toString();
                if (name.startsWith(TEMP_PREFIX)) {
                    continue;
                }
                if (isDirectory(entry)) {
                    out.append("d\t0\t").append(name).append('\n');
                } else if (isFile(entry)) {
                    out.append("f\t").append(Files.size(entry)).append('\t').append(name).append('\n');
                }
            }
        }
        return text(out.toString());
    }

    private void mkdir(Path dir) throws IOException, FsException {
        if (isRoot(dir) || Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            throw new FsException(EEXIST);
        }
        Path parent = dir.getParent();
        if (!isRoot(parent) && !isDirectory(parent)) {
            throw new FsException(ENOENT);
        }
        if (usage()[1] >= MAX_ENTRIES) {
            throw new FsException(ENOSPC);
        }
        Files.createDirectories(dir);
        usageBytes = -1;
    }

    private void remove(Path path) throws IOException, FsException {
        if (isRoot(path)) {
            throw new FsException(EACCES);
        }
        if (isDirectory(path)) {
            try (Stream<Path> entries = Files.list(path)) {
                if (entries.findAny().isPresent()) {
                    throw new FsException(ENOTEMPTY);
                }
            }
        } else if (!isFile(path)) {
            throw new FsException(ENOENT);
        }
        Files.delete(path);
        usageBytes = -1;
    }

    private void rename(Path from, Path to) throws IOException, FsException {
        if (isRoot(from) || isRoot(to)) {
            throw new FsException(EACCES);
        }
        if (!Files.exists(from, LinkOption.NOFOLLOW_LINKS)) {
            throw new FsException(ENOENT);
        }
        Path parent = to.getParent();
        if (!isRoot(parent) && !isDirectory(parent)) {
            throw new FsException(ENOENT);
        }
        if (isDirectory(to) || (isDirectory(from) && Files.exists(to, LinkOption.NOFOLLOW_LINKS))) {
            throw new FsException(EEXIST);
        }
        if (isDirectory(from) && to.startsWith(from)) {
            throw new FsException(EINVAL); // a folder can't be moved into itself
        }
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        usageBytes = -1;
    }

    /**
     * {total bytes of files, number of files and folders}. Found by walking the drive (they
     * are small), then remembered briefly; an admin may also change files from outside.
     */
    private long[] usage() throws IOException {
        long now = System.nanoTime();
        if (usageBytes < 0 || now - usageMeasuredAt > USAGE_MAX_AGE_NANOS) {
            long bytes = 0;
            long entries = 0;
            if (Files.exists(root)) {
                try (Stream<Path> all = Files.walk(root)) {
                    for (Path path : (Iterable<Path>) all::iterator) {
                        if (path.equals(root)) {
                            continue;
                        }
                        entries++;
                        if (isFile(path)) {
                            bytes += Files.size(path);
                        }
                    }
                }
            }
            usageBytes = bytes;
            usageEntries = entries;
            usageMeasuredAt = now;
        }
        return new long[] {usageBytes, usageEntries};
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
