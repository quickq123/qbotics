# What the game knows about items and recipes: every mod's, as this world has them (design doc §17.8).
"""Items and recipes as this world has them: every mod's, including the modpack's own changes.

    import game
    for r in game.recipes("crafting table"):     # the recipes that make it
        print(r["type"], r)
    game.uses("iron_ingot")                     # the recipes it is used in
    game.item("coal")                           # {"name": "Coal", "fuel_ticks": 1600, "tags": [...], ...}
    game.find_items("*_ingot")                  # [{"id": "minecraft:iron_ingot", "name": "Iron Ingot"}, ...]
    game.fuels()                                # what burns, best first
    p = game.plan("torch", 16, store)           # the whole tree, from what the store holds:
    p["steps"], p["missing"], p["max_craftable"]

A recipe the game can describe for a program also has "craft": its station (the block that
makes it), each input by slot of that block's screen with the filter to use and the items that
fit, the output, and what is left over; logistics.Recipe.from_game makes it into a Recipe that
crafts it. game.plan's steps carry the same, so a whole plan can be carried out:

    from logistics import Recipe
    for step in game.plan("chest", 4, store)["steps"]:
        recipe = Recipe.from_game(step, sources=store, destination=store)
        with crafter.open_screen() as screen:        # a bus on that step's station
            recipe.craft(step["runs"], screen)

An item can be given as its full name (minecraft:crafting_table), its name without the mod
(crafting_table), or its display name ("Crafting Table"). A recipe is a dict in the form the
game loaded it from: "type" says which table or machine ("minecraft:crafting_shaped",
"minecraft:smelting", or another mod's), and an ingredient written "#c:ingots/iron" is a tag:
any item carrying it. The shell has the same: recipe, item and items."""
from logistics import _call, NetworkError


def recipes(item, limit=10):
    """The recipes that make an item, as a list of dicts (at most limit, up to 50).
    An empty list: nothing makes it (it is found, dropped, traded...)."""
    return _call("game_recipes", item, False, limit)["recipes"]


def uses(item, limit=10):
    """The recipes an item is an ingredient of, as a list of dicts (at most limit, up to 50),
    those that take it by a tag it carries included."""
    return _call("game_recipes", item, True, limit)["recipes"]


def item(name):
    """One item: its id, display name, mod, tags, stack size, durability, fuel and food
    values, and how many recipes make and use it. NetworkError if there is no such item."""
    return _call("game_item", name)


def find_items(query, limit=40):
    """Items matching a word ("copper"), a pattern ("*_ingot"), a tag ("#c:ores") or a mod
    ("@create"): a list of {"id", "name"} (at most limit, up to 50)."""
    return _call("game_find", query, limit)["items"]


def fuels(limit=20):
    """Every item that burns in a furnace, best first: [{"id", "name", "ticks", "smelts"}, ...]
    (smelts: how many items one smelts)."""
    return _call("game_fuels", limit)


def _have(have):
    # What is in storage, as a program may have it: {item: count}, a device (a bus, an
    # interface), a list of devices, or contents() (a list of {"item", "count"}).
    if have is None:
        return {}
    if isinstance(have, dict):
        return have
    if hasattr(have, "contents"):
        have = [have]
    total = {}
    for one in have:
        stacks = one.contents() if hasattr(one, "contents") else [one]
        for s in stacks:
            if isinstance(s, dict) and "item" in s:
                total[s["item"]] = total.get(s["item"], 0) + s.get("count", 0)
    return total


def plan(item, count=1, have=None, stations=None):
    """How to make count of an item from what is in storage, worked out by the server: the
    whole tree of recipes. have: what there is to use (a device, a list of devices,
    contents(), or {item: count}; none: nothing). stations: the blocks the program can work
    (["crafting_table", "furnace"]...; none: any). Gives a dict:
      steps          the recipes in the order to do them: {"recipe", "station", "runs",
                     "makes": {"item", "count"}, "uses": [{"item", "count"}], "craft"}
      from_storage   what it takes from storage, [{"item", "count"}]
      missing        what isn't there and can't be made, [{"item", "count"}]
      ok             True if nothing is missing
      max_craftable  the most that could be made from what is there"""
    return _call("game_plan", item, count, _have(have), list(stations) if stations else [])


def _shell(command, args):
    # The shell's recipe, item and items commands.
    import json
    as_json = "--json" in args
    if command == "items" and "--fuels" in args:
        found = _call("game_fuels", 50)
        if as_json:
            print(json.dumps(found))
            return
        for f in found:
            print("%-40s %6d ticks  smelts %s" % (f["id"], f["ticks"], f["smelts"]))
        return
    use = "--uses" in args
    limit = 10
    words = []
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--limit" and i + 1 < len(args):
            try:
                limit = int(args[i + 1])
            except ValueError:
                pass
            i += 2
            continue
        if not a.startswith("--"):
            words.append(a)
        i += 1
    what = " ".join(words)
    if not what:
        print({"recipe": "recipe [--uses] [--json] [--limit n] <item>   the recipes that make an item (--uses: that use it)",
               "item": "item [--json] <item>   an item's names, tags, stack size, fuel and food values",
               "items": "items [--json] <word, *pattern*, #tag or @mod>   find items (--fuels: what burns, best first)"}[command])
        return
    try:
        if command == "recipe":
            found = _call("game_recipes", what, use, limit)
            if as_json:
                print(json.dumps(found["recipes"]))
                return
            total = found["total"]
            if total == 0:
                print("No recipe " + ("uses " if use else "makes ") + found["item"] + "." + ("" if use else " It is found, dropped, traded or made another way."))
                return
            print("%d recipe%s %s %s%s:" % (total, "" if total == 1 else "s", "use" if use else "make", found["item"],
                                           "" if total <= len(found["recipes"]) else " (the first %d; --limit n for more)" % len(found["recipes"])))
            for r in found["recipes"]:
                rest = {k: v for k, v in r.items() if k not in ("id", "type", "craft")}
                print("- %s  [%s]" % (r["id"], r.get("type", "?")))
                craft = r.get("craft")
                if craft:
                    # Slot by slot, as the station's screen numbers them.
                    print("  at a %s: %s -> slot %s: %d %s" % (craft["station"].split(":")[-1],
                          ", ".join("slot %d: %s" % (i["slot"], i["item"]) for i in craft["inputs"]),
                          craft["output"].get("slot", "-"), craft["output"]["count"], craft["output"]["item"]))
                else:
                    print("  " + json.dumps(rest))
        elif command == "item":
            info = _call("game_item", what)
            if as_json:
                print(json.dumps(info))
                return
            for key in ("id", "name", "mod", "stack_size", "durability", "fuel_ticks", "food_nutrition", "food_saturation",
                        "recipes_making_it", "recipes_using_it"):
                if key in info:
                    print("%-18s %s" % (key, info[key]))
            print("%-18s %s" % ("tags", " ".join(info["tags"]) if info["tags"] else "(none)"))
        else:
            found = _call("game_find", what, 50 if as_json else 40)
            if as_json:
                print(json.dumps(found["items"]))
                return
            for one in found["items"]:
                print("%-40s %s" % (one["id"], one["name"]))
            total = found["total"]
            print("%d item%s%s" % (total, "" if total == 1 else "s", "" if total <= len(found["items"]) else ", the first %d shown" % len(found["items"])))
    except NetworkError as e:
        print(e)
