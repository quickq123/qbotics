# Qbotics filters: a guide

Wherever a program names an item or a fluid (in `move`, in `only=` and `without=`, in
`count`, in `watch`, in slot filters), it can write either **one name** or a **filter** that
many things pass. This guide covers how filters are written, how to find the names and tags
to put in them, and a set of tags that are useful to know.

The short version is in `reference.md` §4. Filters work the same for fluids as for items.

---

## 1. Finding out what an item is called, and which tags it has

**In the game: the Network Probe.** Hold the probe in your main hand (either mode) and hover
over any item in any inventory, including JEI's list and the creative menu.

- The tooltip gains a line with the item's **exact name**, such as `minecraft:raw_iron`. For a
  bucket it also shows the fluid's name.
- **Ctrl+C** copies that name, ready to paste into a program.
- **Hold Shift** to list the item's **tags**, such as `#c:raw_materials/iron`. Each one can go
  straight into a filter. Up to 8 are shown, with a count of the rest.
- With Shift held, a stack that carries data (an enchanted book, a potion, a worn tool) also
  shows its **data words**, such as `enchant=mending enchanted` (§2b), and a filter naming
  **exactly that stack** in the game's own syntax (§2c). **Ctrl+Shift+C** copies that exact filter.

**With JEI installed**, its search box takes the same words as Qbotics filters, so you can try
a filter there first: type `$c:ores` or `@create -*_slab` and see what comes up.

**In a program**, mistakes tell you what went wrong:

- A name that doesn't exist is an error that suggests close matches
  (`"iron_ore_raw"` → did you mean `minecraft:raw_iron`?).
- A filter that nothing in the game passes is also an error, so a mistyped tag is caught
  rather than silently matching nothing.

---

## 2. The words

| Written | Means | Example |
|---|---|---|
| `minecraft:coal` | That one item | `move(a, b, "minecraft:coal", 16)` |
| `coal` | The same; `minecraft:` is assumed for a bare name | `"coal"` |
| `*` and `?` | Wildcards: `*` is any run of characters, `?` is one character | `"*_log"`, `"?_wool"` |
| `name:*pattern*` | With a colon, the wildcard covers the whole name, including the mod | `"minecraft:*_ore"` |
| `@mod` | Everything from a mod, by its ID or its display name; part of either is enough | `"@create"`, `"@mekanism"` |
| `#namespace:tag` | Everything carrying a tag | `"#c:ores"`, `"#minecraft:logs"` |
| `$namespace:tag` | The same as `#` (JEI's tag prefix) | `"$c:ores"` |
| `#tag` (no namespace) | Matches either `minecraft:tag` or `c:tag`, whichever exists (both, if both do) | `"#logs"`, `"#ingots"` |
| `word word` | Several words separated by spaces: **all** must hold | `"@minecraft *_ore"` |
| `-word` | Must **not** hold. Works with any kind of word | `"-*_slab"`, `"-#c:ores"`, `"-@minecraft"` |
| `a \| b` | Alternatives: **either** may hold | `"coal \| charcoal"` |

How they combine: a filter is split at each `|` into alternatives. Within one alternative,
every space-separated word must hold. An item passes if any alternative passes.

```
"@minecraft *_ore -deepslate_*"       vanilla ores, but not the deepslate ones
"#c:ingots | #c:nuggets"              any ingot or nugget
"*_log -stripped_* | *_wood"          unstripped logs, or any wood block
"#c:foods -#c:foods/food_poisoning"   food that won't poison you
```

Upper and lower case don't matter. A filter can be up to 4,000 characters.

Without a colon, a wildcard looks only at the part after the colon, so `"*_ore"` finds ores
from every mod. Add `@minecraft` (or write `"minecraft:*_ore"`) to keep to one mod.

---

## 2b. Words about one stack's data

Every name, tag and wildcard above looks only at **what kind** of item something is. But two
stacks of one item can differ: one enchanted book holds Mending, another Sharpness; one potion
is Strength, another Swiftness; one pickaxe is worn, another new. What sets a stack apart is
its **data** (the game calls them data components; older versions called it NBT). These words
look at it:

| Word | Passes a stack that | Example |
|---|---|---|
| `enchant=name` | Has that enchantment, on it or (a book) stored in it, at any level | `"enchanted_book enchant=mending"`, `"#c:tools enchant=silk_touch"` |
| `effect=name` | Is a potion (or tipped arrow) with that effect, at any strength or length | `"potion effect=strength"`, `"*potion effect=fire_resistance"` |
| `damaged` | Has lost some durability | `"#c:tools damaged"` |
| `undamaged` | Hasn't (anything that can't be damaged is undamaged) | `"#c:armors undamaged"` |
| `enchanted` | Has any enchantment, on it or stored | `"#c:tools enchanted"` |
| `named` | Has been renamed on an anvil | `"*_sword named"` |

A name after `=` without a colon means Minecraft's: `enchant=mending` is `minecraft:mending`.
For another mod's, write it in full: `enchant=apotheosis:capturing`. Effect names are the
effect's, not the potion's: Swiftness is `effect=speed`, Healing `effect=instant_health`; the probe
(§1) shows the right word.

They combine with every other word: `"enchanted_book -enchant=mending"` is every book but
Mending ones; `"#c:tools damaged | #c:armors damaged"` is gear to repair.

Where only a kind of item is known, not a stack (the Interface slot's picture, say), these
words are passed over.

## 2c. The game's own item syntax

For anything the words above don't cover, a word with `[...]` in it is read by the game
itself, as the `/clear` and `/execute if items` commands read it:

```python
"minecraft:potion[minecraft:potion_contents={potion:'minecraft:strong_strength'}]"   # exactly Strength II
"*[minecraft:damage~{durability:{max:10}}]"                                        # nearly broken, anything
"#c:tools[!minecraft:enchantments]"                                                 # tools with no enchantments
```

- Before the `[` comes an item, a `#tag`, or `*` for any item, written as the game writes them
  (not Qbotics' wildcards or `@mod`).
- `component=value` must equal; `component~predicate` tests (as `damage~{durability:...}`);
  `!component` must be missing.
- You rarely need to write it yourself. The probe's Shift view shows it for any stack, and
  `contents(details=True)` gives it to a program:

```python
for c in chest.contents(details=True):
    print(c["item"], c["words"], c["filter"])
#  minecraft:potion ['effect=strength'] minecraft:potion[minecraft:potion_contents={potion:"minecraft:strength"}]
```

It is an ordinary word, so it combines like any other: `"-named potion[...]"`.

## 2d. Building filters in a program: `Filter`

A filter can be named once and used anywhere, built from others:

```python
from logistics import Filter

junk      = Filter(only=["#c:stones", "dirt", "gravel", "*_seeds"])
vanilla   = Filter(only="@minecraft", without="*stone")
treasure  = Filter(only=["#c:gems", "#c:ingots", "enchanted_book enchant=mending"])
keep      = (vanilla | treasure) & ~junk

move(drop_off, store.destination(only=keep))
net.bus("treasure").set_filter(treasure)
print(store.count(treasure))
```

| Made with | Means |
|---|---|
| `Filter(only=x)` | What `x` passes: a name, filter words, another `Filter`, or a list of them (any may hold) |
| `Filter(only=x, without=y)` | What `x` passes but nothing in `y` does. With no `only=`, everything except `y` |
| `a \| b` | Either |
| `a & b` | Both |
| `~a` | Not |

A `Filter` goes wherever a name or filter does: `move`, `count`, `watch`, `only=` and
`without=`, `set_filter()`. It is turned into the filter words above (`str(f)` shows them), so a
bus keeps it after the program ends. A good place for a base's filters is one file, say
`/lib/filters.py`, that every program imports.

---

## 3. Where filters go

```python
from logistics import network, move, Face, Slot
net = network()
store = net.interface("store")
```

**As the thing to move:**

```python
move(store, net.bus("furnace"), "#c:raw_materials", 64)
```

**On a destination, as an allowlist or a blocklist.** The end then only ever handles those
things, whatever `move` is asked for:

```python
gear  = net.bus("gear").destination(only=["#c:tools", "#c:armors", "elytra"])
ores  = net.bus("ores").destination(only="#c:ores | #c:raw_materials | #c:gems")
trash = net.bus("trash").destination(without=["#c:tools", "#c:armors", "#c:ores"])
```

`only=` and `without=` take one filter or a list. A list is the same as joining the items with `|`.

**On a source**, to say what may be taken from it:

```python
move(store.source(without="#c:tools"), trash)      # never empties tools out of the store
```

**In counting and watching:**

```python
store.count("#c:ingots")
net.bus("chest").watch("#c:ores")
```

**In an Interface slot filter**, kept by the block for every program:

```python
store.set_slot_filter(0, "#c:ingots/iron")
```

### Sorting into several chests

`move` fills destinations in the order given, and each item goes to the first one that takes
it. Put the most specific chests first and a catch-all last:

```python
gear  = net.bus("gear").destination(only=["#c:tools", "#c:armors", "elytra"])
ores  = net.bus("ores").destination(only=["#c:ores", "#c:raw_materials", "#c:gems",
                                          "#c:dusts", "#c:nuggets", "coal", "flint"])
food  = net.bus("food").destination(only="#c:foods")
rest  = net.bus("rest")                      # no list: takes anything

move(store, [gear, ores, food, rest])
```

A destination with no list takes everything, so if it comes first, the chests after it never
get anything.

---

## 4. Useful tags

These all exist in Minecraft 26.1.2 with NeoForge. The `c:` tags are shared by NeoForge and
other mods, so modded items usually join them too. To check a particular item, hold Shift over
it with the probe (§1).

### Tools, weapons and armour

| Tag | Holds |
|---|---|
| `#c:tools` | Every tool and weapon below, plus bows, crossbows, tridents, maces, shields, shears, fishing rods, flint and steel, brushes and wrenches |
| `#minecraft:pickaxes`, `#minecraft:axes`, `#minecraft:shovels`, `#minecraft:hoes`, `#minecraft:swords`, `#minecraft:spears` | One kind of tool each |
| `#c:tools/mining_tool`, `#c:tools/melee_weapon`, `#c:tools/ranged_weapon` | Tools by use |
| `#c:tools/bow`, `#c:tools/crossbow`, `#c:tools/trident`, `#c:tools/shield`, `#c:tools/shear`, `#c:tools/fishing_rod` | One each |
| `#c:armors` | All armour: worn armour plus horse, wolf and nautilus armour |
| `#c:armors/humanoid` | Armour players wear |
| `#minecraft:head_armor`, `#minecraft:chest_armor`, `#minecraft:leg_armor`, `#minecraft:foot_armor` | By slot |
| `#minecraft:enchantable/durability` | Anything that takes damage, a broad "gear" test |

The elytra is in none of the armour tags (it is in `#minecraft:enchantable/durability`). Name it
directly: `"elytra"`. The turtle shell is in `#minecraft:head_armor`.

### Ores and mining drops

| Tag | Holds |
|---|---|
| `#c:ores` | Ore blocks (from Silk Touch): coal, copper, iron, gold, redstone, lapis, diamond, emerald, nether quartz, ancient debris |
| `#c:ores/iron`, `#c:ores/gold`, `#c:ores/diamond`, ... | One ore, stone and deepslate together. Also `copper`, `coal`, `emerald`, `lapis`, `redstone`, `quartz`, `netherite_scrap` |
| `#minecraft:iron_ores`, `#minecraft:coal_ores`, ... | Vanilla's version of the same |
| `#c:raw_materials` | Raw iron, raw copper, raw gold (`#c:raw_materials/iron` and so on for one) |
| `#c:gems` | Diamond, emerald, lapis, quartz, amethyst shard, prismarine crystals |
| `#c:dusts` | Redstone and glowstone dust |
| `#c:nuggets` | Iron, gold and copper nuggets |
| `#minecraft:coals` | Coal and charcoal (use `"coal"` alone to leave charcoal out) |
| `#c:ingots` | Iron, gold, copper, netherite ingots (`#c:ingots/iron` for one) |
| `#c:storage_blocks` | Blocks of iron, gold, diamond, coal, redstone, raw iron and the like |

Not in any tag: `flint` and `clay_ball`. Name them directly. The amethyst cluster is in `#c:clusters`.

### Stone, dirt and other digging junk

| Tag | Holds |
|---|---|
| `#c:stones` | Stone, deepslate, tuff, andesite, diorite, granite |
| `#c:cobblestones` | Cobblestone, mossy, infested, and cobbled deepslate |
| `#minecraft:dirt` | Dirt, coarse dirt, rooted dirt |
| `#c:gravels`, `#c:sands`, `#c:netherracks`, `#c:end_stones` | As named |
| `#minecraft:stone_tool_materials` | The stones a stone tool can be made of |

A Qbot or a player mining with a plain pickaxe brings back cobblestone, not stone, so a junk
chest usually wants `["#c:cobblestones", "#c:stones", "#minecraft:dirt", "#c:gravels", "#c:netherracks"]`.

### Wood and plants

| Tag | Holds |
|---|---|
| `#minecraft:logs` | All logs and wood, stripped too (`#minecraft:oak_logs` and so on for one tree) |
| `#c:natural_logs`, `#c:stripped_logs` | Unstripped or stripped only |
| `#minecraft:planks`, `#minecraft:saplings`, `#minecraft:leaves` | As named |
| `#c:crops`, `#c:seeds` | Wheat, carrots, potatoes, beetroot and so on; their seeds |
| `#minecraft:flowers`, `#c:mushrooms` | As named |

### Food and mob drops

| Tag | Holds |
|---|---|
| `#c:foods` | All food. Narrower: `#c:foods/raw_meat`, `#c:foods/cooked_meat`, `#c:foods/raw_fish`, `#c:foods/cooked_fish`, `#c:foods/fruit`, `#c:foods/vegetable`, `#c:foods/golden`, `#c:foods/soup` |
| `#c:foods/food_poisoning` | Food with a bad effect (rotten flesh, spider eye and the like) |
| `#c:bones`, `#c:strings`, `#c:feathers`, `#c:leathers`, `#c:gunpowders`, `#c:slime_balls`, `#c:ender_pearls`, `#c:rods` | Common mob drops |

### Building blocks and other things

| Tag | Holds |
|---|---|
| `#minecraft:slabs`, `#minecraft:stairs`, `#minecraft:walls`, `#minecraft:fences` | As named |
| `#c:glass_blocks`, `#c:glass_panes`, `#c:concretes`, `#minecraft:terracotta`, `#minecraft:wool` | As named |
| `#c:dyes`, `#c:dyed` | Dyes; anything that comes in a dye colour |
| `#c:chests`, `#c:barrels`, `#c:shulker_boxes` | Storage blocks |
| `#c:music_discs`, `#c:potions`, `#c:buckets` | As named |

---

## 5. Things to watch for

- **Block tags aren't item tags.** Minecraft keeps the two apart, even when they share a name.
  The F3 screen lists the tags of the *block you're looking at*. Those are block tags, such as
  `#minecraft:mineable/pickaxe` or a mod's `#c:sand_growables` on bamboo, and they match
  nothing in a chest. A filter made only of them is an error, and the error says it's a block
  tag. For an item's own tags, use the probe with Shift (§1).
- **Ore blocks and ore drops are different.** `#c:ores` is the ore block itself, which you only
  get with Silk Touch. A normal pickaxe drops raw iron, diamond and so on, which are
  `#c:raw_materials` and `#c:gems`.
- **`@minecraft` matches part of a name**, so `@mine` would too. Write the full ID when two mods
  have similar names.
- **A slot holds one kind of item at a time**, even when its Interface filter passes many.
- **To check a modded item**, hover over it with the probe and hold Shift. Mods don't always
  tag everything, and wildcards or `@mod` can fill the gap.
