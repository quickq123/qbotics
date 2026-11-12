# Files. A computer has a drive of its own: files stay there, even when it is broken
# and placed again. This keeps a list of notes in notes.txt.
# Run it:  run notes.py
import os

NOTES = "notes.txt"

# Read what is there already (the first time, there is nothing).
if NOTES in os.listdir("."):
    with open(NOTES) as f:
        notes = [line.strip() for line in f if line.strip()]
else:
    notes = []

print("You have", len(notes), "notes.")
for number, note in enumerate(notes, 1):
    print(" ", number, note)

note = input("A new note (or Enter for none): ")
if note:
    notes.append(note)
    # "w" writes the file afresh; "a" would add to the end.
    with open(NOTES, "w") as f:
        for line in notes:
            f.write(line + "\n")
    print("Saved. Run it again to see it, or:  cat notes.txt")
