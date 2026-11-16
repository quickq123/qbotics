# git for computers (design doc 17.5): clone a GitHub repository onto this computer's drive,
# pull its newest commit, see what you changed, and push your changes back.
#
#     import git
#     git.clone("https://github.com/me/factory", "/factory")
#     git.status("/factory")            # {"modified": [...], "added": [...], "deleted": [...]}
#     git.pull("/factory")
#     git.push("/factory", "Faster smelting")
#
# Through the Uplink, like requests (a laptop has one built in). The game does the work and
# writes the files itself, so a repository may be far larger than a program's memory. Pushing
# uses your GitHub token, set with /qbotics git token; no program can read it.
import json as _json
import os as _os
import _host


class GitError(Exception):
    """git couldn't do it. The message says why."""


def _abs(path):
    if path is None:
        path = _os.getcwd()
    if not path.startswith("/"):
        here = _os.getcwd()
        path = (here if here.endswith("/") else here + "/") + path
    parts = []
    for part in path.split("/"):
        if part == "..":
            if parts:
                parts.pop()
        elif part and part != ".":
            parts.append(part)
    return "/" + "/".join(parts)


def _call(op, folder, extra=None, force=False):
    reply = _host.wait(_host.submit(_json.dumps(["git", op, _abs(folder), extra, force])))
    if reply is None:
        raise GitError("interrupted")
    if not reply.startswith("{"):
        raise GitError(reply)
    reply = _json.loads(reply)
    if "e" in reply:
        raise GitError(reply["e"])
    return reply["r"]


def _name(url):
    name = url.rstrip("/").split("/")[-1]
    return name[:-4] if name.endswith(".git") else name


def clone(url, folder=None):
    """Copies a GitHub repository into a folder (by default one named after it). Returns
    {"files", "branch", "commit", "repo"}. A branch: https://github.com/me/repo/tree/dev."""
    if folder is None:
        url_path = url.split("/tree/")[0]
        folder = _name(url_path)
    return _call("clone", folder, url)


def pull(folder=None, force=False):
    """Brings a cloned folder up to the repository's newest commit. Refuses if you changed
    files there, unless force=True (which drops your changes)."""
    return _call("pull", folder, None, force)


def status(folder=None):
    """What changed since the last clone, pull or push: {"modified", "added", "deleted", ...}."""
    return _call("status", folder)


def push(folder=None, message=None):
    """Makes a commit of your changes and pushes it to the repository's branch. Needs your
    token (/qbotics git token) and a server that allows pushing."""
    return _call("push", folder, message)


def _print_status(s):
    print("On %s, branch %s, at %s" % (s["repo"], s["branch"], s["commit"]))
    shown = False
    for kind, mark in (("modified", "M"), ("added", "A"), ("deleted", "D")):
        for path in s[kind]:
            print("  %s %s" % (mark, path))
            shown = True
    if not shown:
        print("Nothing changed.")


_USAGE = """git clone helpme             examples of everything, by topic (no internet needed)
git clone <url> [folder]     copy a GitHub repository here
git pull [folder] [--force]   get its newest commit (--force drops your changes)
git status [folder]           what you changed
git push -m "message" [folder]   send your changes (needs /qbotics git token)"""


def _main(args):
    """The shell's git command."""
    if not args or args[0] in ("help", "-h", "--help"):
        print(_USAGE)
        return
    op, rest = args[0], args[1:]
    force = "--force" in rest or "-f" in rest
    rest = [a for a in rest if a not in ("--force", "-f")]
    message = None
    if "-m" in rest:
        i = rest.index("-m")
        if i + 1 >= len(rest):
            raise SystemExit('git push -m "message"')
        message = rest[i + 1]
        rest = rest[:i] + rest[i + 2:]
    folder = rest[0] if rest else None
    try:
        if op == "clone":
            if not rest:
                raise SystemExit("git clone <url> [folder]")
            print("Cloning " + rest[0] + " ...")
            r = clone(rest[0], rest[1] if len(rest) > 1 else None)
            print("%d files from %s, branch %s, at %s." % (r["files"], r["repo"], r["branch"], r["commit"]))
            where = rest[1] if len(rest) > 1 else _name(rest[0].split("/tree/")[0])
            try:
                _os.stat(where + "/README.md")
                print("Start with:  read " + where + "/README.md")
            except OSError:
                pass
        elif op == "pull":
            r = pull(folder, force)
            if r["already"]:
                print("Already up to date (%s)." % r["commit"])
            else:
                print("Pulled %s: %d files." % (r["commit"], r["files"]))
        elif op == "status":
            _print_status(status(folder))
        elif op == "push":
            r = push(folder, message)
            print("Pushed %s to %s: %d files changed." % (r["commit"], r["branch"], r["files"]))
        else:
            print(_USAGE)
    except GitError as e:
        # Ends as a failure, so a shell script running git stops here.
        raise SystemExit("git: " + str(e))
