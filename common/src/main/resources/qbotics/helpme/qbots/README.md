# qbots: robots

A Qbot runs its own program. These run **on the Qbot**. To get them there, from a computer
cabled to the Qbot:

    ssh <the Qbot's name or address>
    git clone helpme
    run /helpme/qbots/tunnel.py

To make one the Qbot's own program (its Run button starts it): `cp /helpme/qbots/tunnel.py /startup.py`.
`exit` leaves the Qbot.

| File | What it shows | You need |
|---|---|---|
| `tunnel.py` | Digging a two-high tunnel, coming back, and unloading into a chest behind it | An Iron Qbot (a chest behind it, if you like) |
| `camera.py` | Taking pictures of what is in front of it, showing them on a screen, and checking what they show | A Web Panel touching the Qbot or cabled to it |
| `remote/` | **Driving a Qbot from your tablet**, a good first project: `startup.py` runs on the Qbot, puts buttons and the Qbot's camera view on your tablet, and moves when a button is pressed (see `remote/README.md`). `remote/base/` does the same from a base computer, by radio | An Iron Qbot and a Tablet |

A Qbot reads the world where it stands with `env` (time, weather, biome: see
`/helpme/world/7_day_night.py`, which runs on a Qbot too), and fights with `qbot.attack()`,
a full swing each time with whatever it holds.
