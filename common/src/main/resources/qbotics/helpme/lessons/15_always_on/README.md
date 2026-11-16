# Lesson 15: Always On

**Earns:** *Always On*: a program started by itself keeps running for a whole game day (20
minutes). **You need:** your sorter (lesson 5), or any program that uses `events.run()`.

## The idea

So far you have started programs by typing `run`. A base's programs should start by
themselves, whenever the computer starts: when the area loads, after a server restart, or when
someone breaks and replaces the computer. Two files at the top of the drive do that:

| File | Runs when the computer starts |
|---|---|
| `/startup.sh` | Shell commands, one a line: `run /my/sorter.py` |
| `/startup.py` | A Python program |

## Try it

    edit /startup.sh

Put the command that starts your program in it, and save:

    echo Starting the sorter
    run /my/sorter.py

Now switch the computer off and on: `shutdown`, then right-click it. The sorter starts by
itself, and keeps going while nobody looks.

## Your turn

Leave it running for a game day: one sunrise to the next. The advancement comes up when it
has run that long without stopping.

## Hints

- **Errors stop a program.** One unlucky error in a handler stops `events.run()` and every
  handler with it. `/helpme/buses/factory/safely.py` shows how to print the error and carry on.
- **The area must stay loaded** for the computer to run. A Chunk Loader Upgrade in its antenna
  keeps it loaded (manual 7.3b).
- Ctrl+C at the terminal stops what startup started; `run /my/sorter.py` starts it again.

**Next:** `16_radio`. **More:** `/helpme/buses/factory` (a program made to run for ever).
**Manual:** section 1.1b.
