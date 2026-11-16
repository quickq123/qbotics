# Trading with a villager. An Entity Sensor next to it, and a storage holding emeralds (and
# whatever else it asks for).
# Run it:  run 3_trader.py
from logistics import network

WANT = "enchanted_book"       # the item to buy (None: only list the offers)
WORD = None                   # and what it must carry, e.g. "enchant=mending"; None for any
TIMES = 1                     # how many times to make each trade that sells it

net = network()
sensor = net.entity_sensor()
store = net.storage("chest*")

villager = sensor.nearest("villager")
if villager is None:
    raise SystemExit("No villager in the sensor's area.")
print(villager.name, "-", villager.data.get("profession"), "level", villager.data.get("level"))

def wanted(sell):
    if WANT is None or sell["item"].split(":")[-1] != WANT.split(":")[-1]:
        return False
    return WORD is None or WORD in sell.get("words", [])

for offer in villager.offers():
    buy, sell = offer["buy"], offer["sell"]
    price = "%d %s" % (buy["count"], buy["item"])
    if offer.get("buy_b"):
        price += " + %d %s" % (offer["buy_b"]["count"], offer["buy_b"]["item"])
    words = " ".join(sell.get("words", []))         # an enchanted book says what it holds
    print("  %d: %s for %d %s %s%s" % (offer["index"], price, sell["count"], sell["item"], words,
                                      "  (sold out)" if offer["out_of_stock"] else ""))
    if wanted(sell) and not offer["out_of_stock"]:
        # The price comes from the store, checked before anything moves; what it sells goes
        # back to the store. It stops when the villager runs out or the price can't be paid.
        made = villager.trade(offer, store, times=TIMES)
        print("     bought it", made, "times")
