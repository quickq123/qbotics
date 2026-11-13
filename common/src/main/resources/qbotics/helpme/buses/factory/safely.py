# One job's mistake shouldn't stop the others. An error in any function that events calls ends
# events.run(), and with it every job. safely() wraps a function so that an error is printed,
# with the job's name, and the program carries on.
#
#     events.on_change(inbox, safely("sorter", sort))
#
# The wrapped function is called with whatever events gives it (the change, or nothing for a
# timer). Ctrl+C still stops the program: it isn't an Exception.
import sys


def safely(job, function):
    def wrapped(*args):
        try:
            function(*args)
        except Exception as error:
            print("[" + job + "] went wrong:")
            sys.print_exception(error)     # where in the job's file, as Python reports it
    return wrapped
