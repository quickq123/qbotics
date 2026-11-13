package qbotics.engine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A computer's files as its programs see them: its own drive, plus the hard drives in the
 * drive bays on its cable network under {@code /net/<bay>/<drive>/} (design doc §17.6).
 *
 * <p>The mounts are replaced whole by the server as bays come and go and drives go in and
 * out; a program reading a drive that has just been taken out gets "no such file". Moving a
 * file from one drive to another copies it and removes the original; folders can't be moved
 * between drives, only copied file by file.
 */
public final class MountedFileSystem implements HostFileSystem {
    /** The folder the drive bays appear in. */
    public static final String NET = "net";

    private final HostFileSystem own;
    /** Bay name, then drive name, to the drive's files. Replaced whole, never changed in place. */
    private volatile Map<String, Map<String, HostFileSystem>> mounts = Map.of();
    /** Goes up whenever something may have changed what a listing shows: an open editor reloads its explorer. */
    private final java.util.concurrent.atomic.AtomicLong changes = new java.util.concurrent.atomic.AtomicLong();

    public MountedFileSystem(HostFileSystem own) {
        this.own = own;
    }

    /** The bays and drives this computer can see now (called by the server). */
    public void setMounts(Map<String, Map<String, HostFileSystem>> now) {
        if (!now.equals(mounts)) {
            changes.incrementAndGet();
        }
        mounts = now;
    }

    /** A count of the changes made through this computer (writes, new folders, removals, renames, drives plugged in). */
    public long changes() {
        return changes.get();
    }

    public Map<String, Map<String, HostFileSystem>> mounts() {
        return mounts;
    }

    /** Where a path leads: a drive and the path within it, or a place in /net itself. */
    private record Place(HostFileSystem fs, String path, int depth) {
    }

    /** The path's parts, with "." and ".." worked out, as every drive does. */
    private static List<String> parts(String path) {
        List<String> parts = new ArrayList<>();
        for (String part : path.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
                continue;
            }
            parts.add(part);
        }
        return parts;
    }

    /**
     * Works out where a path leads. depth is 0 for the computer's own drive or a mounted
     * drive (with fs set), 1 for /net, 2 for /net/{bay} (with fs null).
     */
    private Place place(String path) throws FsException {
        List<String> parts = parts(path);
        Map<String, Map<String, HostFileSystem>> now = mounts;
        if (parts.isEmpty() || !parts.getFirst().equals(NET) || now.isEmpty()) {
            return new Place(own, path, 0);
        }
        if (parts.size() == 1) {
            return new Place(null, "", 1);
        }
        Map<String, HostFileSystem> bay = now.get(parts.get(1));
        if (bay == null) {
            throw new FsException(ENOENT);
        }
        if (parts.size() == 2) {
            return new Place(null, parts.get(1), 2);
        }
        HostFileSystem drive = bay.get(parts.get(2));
        if (drive == null) {
            throw new FsException(ENOENT);
        }
        return new Place(drive, "/" + String.join("/", parts.subList(3, parts.size())), 0);
    }

    @Override
    public byte[] call(int op, String path, byte[] arg) throws FsException {
        if (op != READ && op != STAT && op != LIST) {
            changes.incrementAndGet(); // counted even if it fails: it may have done part of its work
        }
        Place place = place(path);
        if (place.fs() == null) {
            return netFolder(op, place);
        }
        if (op == RENAME) {
            Place to = place(new String(arg, StandardCharsets.UTF_8));
            if (to.fs() == null) {
                throw new FsException(EACCES);
            }
            if (to.fs() != place.fs()) {
                return moveBetween(place, to);
            }
            return place.fs().call(op, place.path(), to.path().getBytes(StandardCharsets.UTF_8));
        }
        byte[] result = place.fs().call(op, place.path(), arg);
        if (op == LIST && place.fs() == own && parts(path).isEmpty() && !mounts.isEmpty()) {
            // The computer's own top folder shows /net, where the drive bays are.
            String listing = new String(result, StandardCharsets.UTF_8);
            if (!listing.contains("\t" + NET + "\n")) {
                return ("d\t0\t" + NET + "\n" + listing).getBytes(StandardCharsets.UTF_8);
            }
        }
        return result;
    }

    /** /net and /net/{bay} are folders made of what is plugged in: they can be read, not changed. */
    private byte[] netFolder(int op, Place place) throws FsException {
        switch (op) {
            case STAT:
                return "d\t0\t0".getBytes(StandardCharsets.UTF_8);
            case LIST: {
                StringBuilder out = new StringBuilder();
                Iterable<String> names = place.depth() == 1 ? mounts.keySet() : mounts.getOrDefault(place.path(), Map.of()).keySet();
                List<String> sorted = new ArrayList<>();
                names.forEach(sorted::add);
                sorted.sort(String::compareTo);
                for (String name : sorted) {
                    out.append("d\t0\t").append(name).append('\n');
                }
                return out.toString().getBytes(StandardCharsets.UTF_8);
            }
            case MKDIR:
                throw new FsException(EEXIST);
            default:
                throw new FsException(EACCES);
        }
    }

    /** A file moved from one drive to another: copied there, then removed here. */
    private byte[] moveBetween(Place from, Place to) throws FsException {
        String kind = new String(from.fs().call(STAT, from.path(), new byte[0]), StandardCharsets.UTF_8);
        if (kind.startsWith("d")) {
            throw new FsException(EINVAL); // a folder: copy its files instead
        }
        byte[] data = from.fs().call(READ, from.path(), new byte[0]);
        to.fs().call(WRITE, to.path(), data);
        from.fs().call(REMOVE, from.path(), new byte[0]);
        return new byte[0];
    }
}
