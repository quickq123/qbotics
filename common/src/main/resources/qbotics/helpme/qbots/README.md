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
| `remote/` | **Driving a Qbot from your tablet**: `driver.py` runs on the Qbot and obeys orders sent by radio, answering with a picture of what it sees; `controller.py` runs at your base and puts the controls and the view on the tablet (see `remote/README.md`) | An Iron Qbot, and a computer with an antenna your tablet is paired with |
