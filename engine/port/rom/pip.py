# Installing libraries from a hard drive, on this network or another one (design doc §17.6).
"""Installs libraries ("packages") from a hard drive in a Drive Bay into this computer's /lib,
where import finds them. The drive can be on this network, or on another network reached by
radio: a shared repository.

A source says where the packages are: "shared/lib" is the drive called lib in the bay called
shared on this network; "100.64.0.2/shared/lib" is the same behind the antenna at 100.64.0.2.
A package on the drive is a file, name.py, or a folder, name/, with an __init__.py. A package
folder may hold a requirements.txt of its own: those are installed too, from the same source.

From the shell:

    pip install -r requirements.txt
    pip install greetings --source 100.64.0.2/shared/lib
    pip available --source 100.64.0.2/shared/lib
    pip list
    pip uninstall greetings

A requirements.txt lists one package a line, with a --source line saying where they come from:

    --source 100.64.0.2/shared/lib
    greetings
    mathtools        # comments after # are ignored

From a program: pip.install(["greetings"], "100.64.0.2/shared/lib")."""
import os
from logistics import _call, NetworkError

LIB = "/lib"


def _is_dir(path):
    try:
        return os.stat(path)[0] & 0x4000 != 0
    except OSError:
        return False


def _exists(path):
    try:
        os.stat(path)
        return True
    except OSError:
        return False


def _make_dirs(path):
    # Makes a folder and any above it that are missing.
    built = ""
    for part in path.strip("/").split("/"):
        built += "/" + part
        if not _exists(built):
            os.mkdir(built)


def _remove(path):
    # Removes a file, or a folder and everything in it.
    if _is_dir(path):
        for name in os.listdir(path):
            _remove(path + "/" + name)
        os.rmdir(path)
    elif _exists(path):
        os.remove(path)


def _read(source, path):
    # The whole of one file on the source drive, in as many pieces as it takes.
    text = ""
    offset = 0
    while True:
        part = _call("repo_read", source, path, offset)
        text += part["data"]
        if not part["more"]:
            return text
        offset = part["next"]


def available(source):
    """The names of the packages on a source drive."""
    return _call("repo_list", source)


def installed():
    """The names of the packages in /lib."""
    if not _is_dir(LIB):
        return []
    names = []
    for name in os.listdir(LIB):
        if name.endswith(".py"):
            names.append(name[:-3])
        elif _is_dir(LIB + "/" + name):
            names.append(name)
    return sorted(names)


def parse_requirements(text, source=None):
    """Reads a requirements.txt: returns (source, [package names]). A --source (or -s)
    line sets where they come from; # starts a comment."""
    names = []
    for line in text.split("\n"):
        line = line.split("#")[0].strip()
        if not line:
            continue
        if line.startswith("--source") or line.startswith("-s "):
            source = line.split(None, 1)[1].strip() if len(line.split(None, 1)) > 1 else source
            continue
        for mark in ("==", ">=", "<=", "~=", ">", "<"):
            if mark in line:
                raise NetworkError("Versions aren't supported yet: write just the name, %s, not %s." % (line.split(mark)[0].strip(), line))
        names.append(line)
    return source, names


def install(names, source, _done=None):
    """Installs packages from a source into /lib, replacing any earlier copies, and what
    each one's own requirements.txt asks for. Returns the names installed."""
    done = [] if _done is None else _done
    _make_dirs(LIB)
    for name in names:
        if name in done:
            continue
        files = _call("repo_tree", source, name)["files"]
        _remove(LIB + "/" + name + ".py")
        _remove(LIB + "/" + name)
        needs = []
        for path in files:
            text = _read(source, path)
            if "/" in path:
                _make_dirs(LIB + "/" + path.rsplit("/", 1)[0])
            with open(LIB + "/" + path, "w") as f:
                f.write(text)
            if path == name + "/requirements.txt":
                needs = parse_requirements(text)[1]
        done.append(name)
        print("installed %s (%d file%s)" % (name, len(files), "" if len(files) == 1 else "s"))
        install(needs, source, done)
    return done


def uninstall(name):
    """Removes a package from /lib."""
    if not (_exists(LIB + "/" + name + ".py") or _is_dir(LIB + "/" + name)):
        raise NetworkError("%s isn't installed. Installed: %s" % (name, ", ".join(installed()) or "nothing"))
    _remove(LIB + "/" + name + ".py")
    _remove(LIB + "/" + name)
    print("removed", name)


_USAGE = """pip install -r requirements.txt        install what a requirements file lists
pip install <name> ... --source <source> install packages from a source
pip available --source <source>          what packages a source has
pip list                                 what is installed in /lib
pip uninstall <name>                     remove an installed package
A source is a bay and a drive: shared/lib on this network, or 100.64.0.2/shared/lib
behind another antenna."""


def main(argv):
    """The shell's pip command."""
    args = list(argv)
    source = None
    for flag in ("--source", "-s"):
        if flag in args:
            at = args.index(flag)
            if at + 1 >= len(args):
                print("%s needs a source after it, such as 100.64.0.2/shared/lib" % flag)
                return
            source = args[at + 1]
            del args[at:at + 2]
    if not args or args[0] in ("help", "-h", "--help"):
        print(_USAGE)
        return
    try:
        command = args[0]
        if command == "install":
            names = args[1:]
            if "-r" in names:
                at = names.index("-r")
                path = names[at + 1] if at + 1 < len(names) else "requirements.txt"
                with open(path) as f:
                    source, listed = parse_requirements(f.read(), source)
                names = names[:at] + names[at + 2:] + listed
            if not names:
                print("Nothing to install. Name packages, or give a requirements file: pip install -r requirements.txt")
                return
            if source is None:
                print("Say where the packages come from: --source shared/lib, or a --source line in requirements.txt.")
                return
            install(names, source)
        elif command == "available":
            if source is None:
                print("Say which source: pip available --source 100.64.0.2/shared/lib")
                return
            for name in available(source):
                print(name)
        elif command == "list":
            for name in installed():
                print(name)
        elif command == "uninstall":
            for name in args[1:]:
                uninstall(name)
        else:
            print("pip doesn't know '%s'.\n%s" % (command, _USAGE))
    except NetworkError as e:
        print(e)
    except OSError as e:
        print("pip: file error", e)
