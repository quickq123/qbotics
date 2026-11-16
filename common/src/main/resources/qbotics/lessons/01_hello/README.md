# Lesson 1: Hello, World

**Earns:** *Hello, World*. **You need:** a computer (or a laptop).

## Where you are

A computer keeps its files in **folders**, as your own computer does. The lessons are in a
folder called `lessons`, and each lesson has a folder inside it. You are in this one:

    /lessons/01_hello

Read that as "start at the top (`/`), go into `lessons`, then into `01_hello`". That line is a
**path**: an address for a folder or a file. The prompt always shows where you are:

    /lessons/01_hello $

Two commands are all you need to get about:

| Type | It does |
|---|---|
| `cd /lessons/02_network` | Goes to a folder (cd: "change directory") |
| `ls` | Lists what is in the folder you are in |

## The idea

A program is a file of Python that the computer runs, line by line. The first program anyone
writes prints a greeting, to show that writing and running work.

This folder has one ready for you, called `start.py`. Run it:

    run start.py

It prints `hello world`, and the advancement comes up. You have run your first program.

## Your turn

Open it in the editor:

    edit start.py

Change the words, add lines of your own, then save (Ctrl+S), close the editor (Esc) and run it
again with `run start.py`. Some things to try:

    name = "Steve"
    print("I am", name)
    for n in range(3):
        print("counting", n + 1)

## Hints

- **A mistake** shows as a traceback: the last lines say what went wrong and on which line, and
  a **Hint:** under it says what that usually means. The editor underlines mistakes as you type.
- **Typing Python at the prompt** runs one line at a time: type `python`, then `print("hi")`.
  `exit()` comes back. A file is for anything you want to keep and run again.
- Ctrl+C stops a program that runs too long.

**Next:** `cd /lessons/02_network`, then `read README.md`.
