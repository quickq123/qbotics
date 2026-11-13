# The factory: starts every job, then lets events run them. This is the file you run.
# Run it:  run main.py   (Ctrl+C stops it)
#
# Each job is a file of its own with a start() function (see README.md). To add a job, write
# its file and add it to JOBS; to turn one off, take it out.
import events
import sorter
import smelter
import report

JOBS = [sorter, smelter, report]

for job in JOBS:
    job.start()        # does the job once now, and tells events when to do it again
    print("Started", job.__name__)

print("The factory is running. Ctrl+C stops it.")
events.run()           # waits, and calls each job's functions as things happen
