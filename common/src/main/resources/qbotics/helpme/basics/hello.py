# Python on a computer.
# Run it:  run hello.py   (Ctrl+C stops it)

# print() writes a line on the terminal.
print("Hello from a Qbotics computer!")

# input() waits for you to type a line and press Enter.
name = input("What is your name? ")
print("Nice to meet you,", name)

# A loop: the same lines, again and again.
for count in range(3, 0, -1):
    print(count, "...")
print("Lift off!")

# A function: a few lines with a name, used whenever you like.
def blocks_in(width, height, depth):
    return width * height * depth

print("A 16 x 16 x 16 chunk section holds", blocks_in(16, 16, 16), "blocks.")

# Next: change the countdown to start at 10, and run it again (run hello.py).
