def run():
    total = 0
    for i in range(1000000):
        total += i * 2 % 7
    return total


print(run())
