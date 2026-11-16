# Lesson 17: At Your Service

**Earns:** *At Your Service*: a service runs on a Server for a whole game day. **You need:** a
**Server** (the late-game computer) on your base's cables.

## The idea

A computer runs one program at a time, at its terminal. A **Server** runs up to **four
services**: programs in the background, each with its own Python, while its terminal stays free
for you. Each has a light on the rack: green while it runs, red if it failed. A Server starts its
services again whenever it comes back on, so it suits the programs a base runs all the time: the
sorter, the smelters, the status screen, the radio link.

## Try it

On the Server, start a program as a service:

    service start /helpme/server/heartbeat.py
    service list                  # what runs, and how
    service log heartbeat         # what it printed
    service stop heartbeat

## Your turn

Run your own programs as services: the sorter from lesson 5, and the screen you made in lesson 11
(say you saved it as `screen.py` in its folder). The last word is the service's name:

    service start /lessons/05_events/start.py sorter
    service start /lessons/11_monitor/screen.py screen

Leave them running for a game day. Then switch the Server off and on: they start again by
themselves.

## New Python here

- A service's `print`s go to its **log** (`service log sorter`), not to a screen: print what it
  does, so you can see later.
- A program can't be typed to as a service: anything it needs goes in variables at the top.

## Hints

- Services share the Server's drive, so they can share files: one writes, another reads.
- A service can't be typed to: `input()` gets nothing. Talk to it with messages (lesson 16).
- `service help` lists all of it.

**More:** `/helpme/server`. **Manual:** sections 7i and 7i.1.
