# remote/base: drive a Qbot from a computer at your base

The same controller as `../startup.py`, but split in two programs that talk by **radio**.
The Qbot obeys orders; a computer at your base shows the controller on your tablet and sends
the orders. Use this when the base should do more than drive: keep a log of what the Qbot
found, or drive several Qbots from one tablet.

    tablet --(button)--> controller.py --(radio: {"do": "forward"})--> driver
    tablet <--(picture)-- controller.py <--(radio: answer + picture)-- driver

| File | Runs on | What it does |
|---|---|---|
| `driver.py` | The Qbot | Waits for orders as messages (`events.on_message`), carries each out, and answers with what happened, where it is and a picture of what it sees |
| `controller.py` | A computer at your base | Shows the controller on the tablet (`../index.html`), sends an order for each button, and shows each answer and picture |

## Setting it up

1. **The Qbot.** Open its terminal (right-click it with your laptop, or `ssh` to it from a
   computer cabled to it), then:

       git clone helpme
       cp /helpme/qbots/remote/base/driver.py /startup.py
       exit

   Press **Run** on the Qbot's screen. It shows `Ready. My radio address is 100.64.0.9:1001`:
   note that address.
2. **The base.** A computer cabled to an antenna. Pair your tablet with that antenna (sneak
   and right-click the antenna with the tablet). On the computer:

       git clone helpme
       edit /helpme/qbots/remote/base/controller.py
          (put the Qbot's address in QBOT at the top, and save)
       run /helpme/qbots/remote/base/controller.py
3. **Drive.** Right-click in the air with the tablet.

A Qbot's radio takes messages only from its owner's computers and Qbots. To add an order:
in `driver.py`, add it to `ORDERS`; in `../index.html`, add its button; in `controller.py`,
add its word to `ORDERS`.
