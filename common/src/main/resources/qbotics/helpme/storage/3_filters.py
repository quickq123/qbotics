# Filters on what an item carries, and filters made of filters. The buses from 2_sorting.py,
# with two more chests: "chest_books" for enchanted books, "chest_repair" for worn tools.
# Run it:  run 3_filters.py
from logistics import network, move, Filter

net = network()
store = net.storage("chest_*")

# Data words look at one stack, not just its kind:
#   "enchanted_book enchant=mending"   a book holding Mending
#   "potion effect=strength"           a Strength potion
#   "damaged", "undamaged"             worn, or as new
#   "enchanted", "named"               enchanted, or renamed on an anvil
print("Mending books:", store.count("enchanted_book enchant=mending"))
print("Worn tools:", store.count("#c:tools damaged"))

# A Filter is built once from names, words and other Filters, and goes anywhere a name does.
#   only=     what it lets through     without=  what it never does
#   a | b     either      a & b     both      ~a     not a
junk = Filter(only=["#c:stones", "dirt", "gravel", "rotten_flesh"])
books = Filter(only="enchanted_book", without="enchant=vanishing_curse | enchant=binding_curse")
repair = Filter(only=["#c:tools", "#c:armors"]) & "damaged"

net.bus("chest_books").set_filter(books)
net.bus("chest_repair").set_filter(repair)

# Everything but junk goes into the storage; the junk stays in the inbox, to be thrown away.
moved = move(net.bus("inbox"), store.destination(without=junk))
print("Put away", moved, "items, leaving the junk")

# For data no word covers, write the game's own item syntax, as in /clear:
#   "potion[potion_contents={potion:'minecraft:strong_strength'}]"
# Hover over an item holding the Network Probe, with Shift held, to see its words and the
# exact filter naming that stack (Ctrl+Shift+C copies it).
