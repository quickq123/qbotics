class Slot:
    def __init__(self, item, count):
        self.item = item
        self.count = count


def run():
    total = 0
    for round_no in range(20):
        slots = [Slot("minecraft:item_%d" % (i % 50), i) for i in range(2000)]
        counts = {}
        for slot in slots:
            counts[slot.item] = counts.get(slot.item, 0) + slot.count
        total += sum(sorted(counts.values())[:10])
    return total


print(run())
