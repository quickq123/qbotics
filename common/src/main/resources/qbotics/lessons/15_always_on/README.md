# Lesson 15: Always On

**Earns:** *Always On*: a program started by itself keeps running for a whole game day (20
minutes). **You need:** your sorter (lesson 5), or any program that uses `events.run()`.

## The idea

So far you have started programs by typing `run`. A base's programs should start by
themselves, whenever the computer starts: when the area loads, after a server restart, or when
someone breaks and replaces the computer. Two files at the top of the drive do that:

| File | Runs when the computer starts |
|---|---|
| `/startup.sh` | Shell commands, one a line: `run /lessons/05_events/start.py` |
| `/startup.py` | A Python program. Only run if `startup.sh` has no commands in it |

Every computer starts with a `startup.sh` that holds only notes (lines starting with `#`),
which do nothing.

## Try it

    edit /startup.sh

It is at the top of the drive (the `/` at the start says so), so it is the same file wherever
you are. Put the command that starts your sorter (lesson 5) in it, and save:

    echo Starting the sorter
    run /lessons/05_events/start.py

Now switch the computer off and on: `shutdown`, then right-click it. The sorter starts by
itself, and keeps going while nobody looks.

## Your turn

Leave it running for a game day: one sunrise to the next (20 minutes). The advancement comes
up when it has run that long without stopping. It is 20 minutes of real time: sleeping in a
bed doesn't shorten it, and it must be the program that started by itself.

## New Python here

- A program that should never stop is a loop (or `events.run()`). It ends only with an error, Ctrl+C,
  or the computer switching off.
- `try:` ... `except Exception as e:` catches any error, so a program can print it and carry on:

      try:
          sort()
      except Exception as e:
          print("sorting failed:", e)

## Hints

- **Errors stop a program.** One unlucky error in a handler stops `events.run()` and every
  handler with it. `/helpme/buses/factory/safely.py` shows how to print the error and carry on.
- **The area must stay loaded** for the computer to run. A Chunk Loader Upgrade in its antenna
  keeps it loaded (manual 7.3b).
- Ctrl+C at the terminal stops what startup started; `run /lessons/05_events/start.py` starts it
  again. For the advancement it has to start by itself: `shutdown`, then right-click the computer.

**Next:** `cd /lessons/16_radio`. **More:** `/helpme/buses/factory` (a program made to run for ever).
**Manual:** section 1.1b.
