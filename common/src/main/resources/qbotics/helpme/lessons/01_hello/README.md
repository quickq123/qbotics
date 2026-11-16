# Lesson 1: Hello, World

**Earns:** *Hello, World*. **You need:** a computer (or a laptop).

## The idea

A program is a file of Python that the computer runs line by line. The first program anyone
writes prints a greeting, to show that writing, saving and running all work.

## Try it

Make a folder of your own and open a new file in the editor:

    mkdir /my
    edit /my/hello.py

Type this, then save (Ctrl+S) and close the editor (Esc):

    print("hello world")

Back at the prompt, run it:

    run /my/hello.py

It prints `hello world`. That's the whole lesson, and the advancement comes up.

## Your turn

Make it say more. Each `print` is a line:

    name = "Steve"
    print("hello world")
    print("I am", name)
    for n in range(3):
        print("counting", n + 1)

## Hints

- **Typing at the prompt** runs one line at a time: type `python`, then `print("hi")`. `exit()`
  comes back to the shell. A file is for anything you want to keep and run again.
- **A mistake** shows as a traceback: the last line says what went wrong and the line above it
  says where. The editor underlines mistakes as you type.
- Ctrl+C stops a program that runs too long.

**Next:** `02_network`. **More:** `/helpme/basics` (Python on a computer, files, notes).
**Manual:** sections 1 and 2.
