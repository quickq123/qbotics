# Runs once when a computer starts: mounts the computer's drive at "/".
#
# The drive is a real folder on the server (design doc §17.2). This module wraps
# the host's raw file operations (_hostfs) in a MicroPython filesystem object, so
# programs use the ordinary open(), os.listdir(), import and so on.
import io
import vfs
import _hostfs

# Operation numbers; must match DriveFileSystem on the Java side.
_READ = 1
_WRITE = 2
_APPEND = 3
_STAT = 4
_LIST = 5
_MKDIR = 6
_REMOVE = 7
_RENAME = 8

_DIR = 0x4000
_FILE = 0x8000


class _File(io.IOBase):
    """An open file.

    Plain writing ("w" or "a") is streamed: every write() goes straight to the
    drive, so nothing is lost if the program forgets to close the file, and no
    memory is used to hold it. Reading, and the "+" modes that mix reading and
    writing, hold the file in memory and write it back on flush or close."""

    def __init__(self, path, mode):
        self._path = path
        self._text = "b" not in mode
        self._writable = "w" in mode or "a" in mode or "+" in mode
        self._stream = self._writable and "+" not in mode
        self._pos = 0
        self._dirty = False
        self._closed = False
        self._buf = b""
        if "w" in mode:
            _hostfs.call(_WRITE, path, b"")  # create or empty the file now
            if not self._stream:
                self._buf = bytearray()
        elif "a" in mode:
            _hostfs.call(_APPEND, path, b"")  # create the file if it is missing
            if not self._stream:
                self._buf = bytearray(_hostfs.call(_READ, path))
                self._pos = len(self._buf)
        else:
            data = _hostfs.call(_READ, path)  # OSError(ENOENT) if missing
            self._buf = bytearray(data) if self._writable else data

    # -- reading

    def readinto(self, buf):
        n = min(len(buf), len(self._buf) - self._pos)
        buf[:n] = self._buf[self._pos:self._pos + n]
        self._pos += n
        return n

    def _take(self, end):
        if self._text:
            # don't cut a multi-byte character in half
            while end < len(self._buf) and (self._buf[end] & 0xC0) == 0x80:
                end += 1
        data = self._buf[self._pos:end]
        self._pos = end
        return str(data, "utf8") if self._text else bytes(data)

    def read(self, n=-1):
        size = len(self._buf)
        return self._take(size if n is None or n < 0 else min(size, self._pos + n))

    def readline(self):
        buf = self._buf
        end = self._pos
        size = len(buf)
        while end < size:
            end += 1
            if buf[end - 1] == 10:
                break
        return self._take(end)

    def readlines(self):
        return list(self)

    def __iter__(self):
        return self

    def __next__(self):
        line = self.readline()
        if not line:
            raise StopIteration
        return line

    # -- writing

    def write(self, data):
        if not self._writable:
            raise OSError(9)  # EBADF
        if isinstance(data, str):
            data = data.encode()
        if self._stream:
            _hostfs.call(_APPEND, self._path, data)
            self._pos += len(data)
            return len(data)
        if self._pos == len(self._buf):
            self._buf.extend(data)
            self._pos = len(self._buf)
        else:
            end = self._pos + len(data)
            self._buf[self._pos:end] = data
            self._pos = end
        self._dirty = True
        return len(data)

    def flush(self):
        if self._dirty:
            _hostfs.call(_WRITE, self._path, self._buf)
            self._dirty = False

    def seek(self, offset, whence=0):
        if self._stream:
            raise OSError(22)  # EINVAL: open with "+" to move around in a file being written
        if whence == 1:
            offset += self._pos
        elif whence == 2:
            offset += len(self._buf)
        self._pos = max(0, min(len(self._buf), offset))
        return self._pos

    def tell(self):
        return self._pos

    def close(self):
        if not self._closed:
            self._closed = True
            self.flush()

    def ioctl(self, request, arg):
        if request == 4:  # MP_STREAM_CLOSE
            self.close()
            return 0
        if request == 1:  # MP_STREAM_FLUSH
            self.flush()
            return 0
        return -1

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        self.close()


class _Drive:
    def __init__(self):
        self._cwd = "/"

    def _abs(self, path):
        if not path.startswith("/"):
            path = self._cwd + ("" if self._cwd.endswith("/") else "/") + path
        return path

    def mount(self, readonly, mkfs):
        pass

    def umount(self):
        pass

    def open(self, path, mode):
        return _File(self._abs(path), mode)

    def stat(self, path):
        kind, size, mtime = str(_hostfs.call(_STAT, self._abs(path)), "utf8").split("\t")
        mtime = int(mtime)
        return (_DIR if kind == "d" else _FILE, 0, 0, 0, 0, 0, int(size), mtime, mtime, mtime)

    def ilistdir(self, path):
        listing = str(_hostfs.call(_LIST, self._abs(path)), "utf8")
        for entry in listing.split("\n"):
            if entry:
                kind, size, name = entry.split("\t")
                yield (name, _DIR if kind == "d" else _FILE, 0, int(size))

    def mkdir(self, path):
        _hostfs.call(_MKDIR, self._abs(path))

    def remove(self, path):
        _hostfs.call(_REMOVE, self._abs(path))

    def rmdir(self, path):
        _hostfs.call(_REMOVE, self._abs(path))

    def rename(self, old, new):
        _hostfs.call(_RENAME, self._abs(old), self._abs(new))

    def chdir(self, path):
        path = self._abs(path)
        if self.stat(path)[0] != _DIR:
            raise OSError(20)  # ENOTDIR
        self._cwd = path

    def getcwd(self):
        return self._cwd

    def statvfs(self, path):
        return (1024, 1024, 0, 0, 0, 0, 0, 0, 0, 64)


vfs.mount(_Drive(), "/")

# Installed libraries (see pip) live in /lib, where import looks for them.
import sys
if "/lib" not in sys.path:
    sys.path.append("/lib")
