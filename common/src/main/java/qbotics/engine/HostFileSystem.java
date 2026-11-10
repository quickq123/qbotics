package qbotics.engine;

/** What a computer's Python instance can do with files. Every call is a security boundary. */
public interface HostFileSystem {
    // Operation numbers; must match engine/port/rom/_boot.py.
    int READ = 1;
    int WRITE = 2;
    int APPEND = 3;
    int STAT = 4;
    int LIST = 5;
    int MKDIR = 6;
    int REMOVE = 7;
    int RENAME = 8;

    // POSIX error numbers, raised in Python as OSError(errno).
    int ENOENT = 2;
    int EIO = 5;
    int EACCES = 13;
    int EEXIST = 17;
    int ENODEV = 19;
    int ENOTDIR = 20;
    int EISDIR = 21;
    int EINVAL = 22;
    int EFBIG = 27;
    int ENOSPC = 28;
    int ENOTEMPTY = 39;

    /**
     * @param arg extra data: the bytes for WRITE and APPEND, the new path (UTF-8) for RENAME
     * @return the result bytes (empty when the operation has no result)
     */
    byte[] call(int op, String path, byte[] arg) throws FsException;

    /** A file operation failed; becomes OSError(errno) in Python. */
    final class FsException extends Exception {
        public final int errno;

        public FsException(int errno) {
            super("errno " + errno, null, false, false);
            this.errno = errno;
        }
    }

    /** A computer with no drive: every operation fails. */
    HostFileSystem NONE = (op, path, arg) -> {
        throw new FsException(ENODEV);
    };
}
