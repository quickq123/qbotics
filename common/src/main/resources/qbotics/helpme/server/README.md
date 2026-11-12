# server: programs in the background

A **Server** runs up to four programs as services while its terminal stays free, and starts them
again whenever it comes back on. Each has a light on the rack: green running, red failed.

| File | What it shows |
|---|---|
| `heartbeat.py` | A program that keeps running and printing. As a service: `service start /helpme/server/heartbeat.py`, then `service list` and `service log heartbeat` |
