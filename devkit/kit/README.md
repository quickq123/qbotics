# Qbotics Programs

A folder for writing Qbotics programs on your PC, away from the game. VS Code helps as you type,
and one key checks whether a program will work before you take it into the game:

- **Will it compile?** The check uses the game's own mistake finder and the computers' own
  Python (the same one the game runs), so a program that passes here passes in the game.
- **Will it run?** The check lists what the program needs in the game, such as
  *"an Item Serial Bus named "store""*, *"to run on a Qbot"* or *"the file "settings.json" on its
  drive"*. Then you know what to build and name before you run it.

## Setting up (once)

1. Install **VS Code** from https://code.visualstudio.com.
2. In VS Code: **File > Open Folder...** and open this folder (the one holding this README).
3. VS Code offers to install the **Python** extension. Say yes. That gives the hints as you type.

The check needs **Java 25**. If you've played Minecraft 26.1, you already have it: the check finds
the Java that the Minecraft Launcher, the CurseForge app or Prism Launcher brought.

## Writing a program

Put your programs in `programs/` (or anywhere in this folder). Open `programs/hello.py` to start.

- **Hints:** type `net.` or `qbot.` and a list of what's there pops up, each with its help.
  Rest the mouse on a name to read its help again.
- **Check:** press **Ctrl+Shift+B**. The result shows at the bottom. The **Problems** panel
  (**Ctrl+Shift+M**) lists:
  - **errors** (red): the program won't start, or stops at that line;
  - **warnings** (amber): probably a mistake;
  - **needs** (blue): what the game must have for it to run.

  Click one to go to its line.
- **Check everything:** **Terminal > Run Task... > Qbotics: check every program**.

You can also check from a terminal in this folder: `.\check programs\mine.py` on Windows
(`./check.sh programs/mine.py` on a Mac or Linux), or `.\check .` for the whole folder.

### What the check reads, and what it can't know

It reads the program as written, without running it. When a program finds a device by a name
written in it, `net.bus("store")`, the check names it. When the name is worked out while the
program runs (from a list, say), the check says so rather than guess. Whether your chest holds
what the program expects, it can't know. That's for the game.

## Taking a program into the game

Any of these work:

- **Copy and paste** it into the game's editor (`edit mine.py` on a computer).
- **Over the SSH gateway**, if it's on (section 7f of `REFERENCE.md`): `scp -P 2222 mine.py miner@localhost:/`,
  or the SFTP extension for VS Code, which copies each file as you save it.
- **With git:** push this folder to GitHub, then `git clone` it on a computer in the game.

## What's in this folder

| Here | What it is |
|---|---|
| `programs/` | Yours. Start with `hello.py` |
| `examples/` | The examples every computer has in `/helpme`: buses, storage, displays, events, Qbots and more. Open one and check it |
| `REFERENCE.md` | The full manual: every module, device and call |
| `check.cmd`, `check.sh` | The check, for a terminal (Windows, and Mac or Linux) |
| `.qbotics/` | The computers' modules (for the hints) and the check itself. You don't need to change these |
| `__builtins__.pyi` | Tells VS Code about `execfile`, which the computers have built in |

This folder comes with one version of the mod. When the mod updates, get the new folder, and copy
`programs/` across.
