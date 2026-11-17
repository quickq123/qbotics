# Lesson 1: Hello, World

**Earns:** *Hello, World*. **You need:** a computer (or a laptop).

## The screen

A computer's screen has two parts:

- **The terminal** is the panel along the **bottom**. Commands are typed there: `ls`, `run`,
  `read`. The line ending in `$` is the **prompt**: it means the computer is waiting for a
  command. This is where you typed `read README.md`.
- **The editor** is the big panel **above** it. Files open there, to be changed.

Typing goes to whichever part you clicked last. **To type a command, click in the terminal
first.** If your command shows up inside a file, you were still in the editor: delete it,
click in the terminal, and type it again.

This lesson is longer than the terminal, so it stops at `-- more --`. Press **Enter** to read
on. A command typed there is run straight away (and the reading stops), so you can do what a
lesson says as soon as you reach it; `read README.md` shows the lesson again.

**Esc** closes the whole computer, not just the editor. Nothing is lost: right-click the
computer and it is as you left it.

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

The file opens in the editor, above the terminal. Change the words, add lines of your own,
then run it. There are two ways:

- Press **F5**. It saves the file and runs it.
- Or save with **Ctrl+S**, **click in the terminal**, and type `run start.py`. (`run` runs the
  file as it was last saved: what you typed since then is not in it until you save.)

Some things to try:

    name = "Steve"
    print("I am", name)
    for n in range(3):
        print("counting", n + 1)

## New Python here

- `print(...)` shows what is in its brackets. Several things, with commas between, are shown
  with spaces: `print("I am", name)`.
- **Text** goes in quotes: `"hello world"`. Without them, Python thinks it is a name.
- A line starting with `#` is a **comment**: notes for people, which Python skips.
- `name = "Steve"` makes a **variable**: a name for a value, to use again later.

## Hints

- **A mistake** shows as a traceback: the last lines say what went wrong and on which line, and
  a **Hint:** under it says what that usually means. The editor underlines mistakes as you type.
- **Typing Python at the prompt** runs one line at a time: type `python`, then `print("hi")`.
  `exit()` comes back. A file is for anything you want to keep and run again.
- **Ctrl+C stops a program** that runs too long, but only in the terminal: click there first.
  In the editor, Ctrl+C copies text and Ctrl+Z undoes your last change, as in any editor.
- In lesson 3 you will watch a program run one line at a time, with F8.

**Next:** `cd /lessons/02_network`, then `read README.md`.
