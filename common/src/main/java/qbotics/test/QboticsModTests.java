package qbotics.test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import qbotics.QboticsContent;
import qbotics.block.InterfaceBlockEntity;
import qbotics.computer.ServerComputer;

/**
 * Qbotics against real mods (gradlew :neoforge:runModTests, with the mods in
 * neoforge/modtest-mods.txt): what the game tests can't show with vanilla alone. Each looks its
 * mod's things up by name and fails plainly if the mod isn't there.
 */
public final class QboticsModTests {
    private QboticsModTests() {
    }

    public static final Map<String, Integer> TICK_LIMITS = Map.of("modded_crops", 2400, "modded_creature", 2400);

    public static final Map<String, Consumer<GameTestHelper>> ALL = Map.of(
            "modded_crops", QboticsModTests::moddedCrops,
            "modded_creature", QboticsModTests::moddedCreature);

    private static Block block(GameTestHelper helper, String id) {
        Block found = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        helper.assertTrue(found != Blocks.AIR, "the mod providing " + id + " should be loaded");
        return found;
    }

    private static Item item(GameTestHelper helper, String id) {
        Item found = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
        helper.assertTrue(found != Items.AIR, "the mod providing " + id + " should be loaded");
        return found;
    }

    private static InterfaceBlockEntity store(GameTestHelper helper, BlockPos at) {
        helper.setBlock(at, QboticsContent.INTERFACE_BLOCK.get());
        InterfaceBlockEntity store = helper.getBlockEntity(at, InterfaceBlockEntity.class);
        for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
            store.setFilter(slot, qbotics.network.NameMatch.parse(BuiltInRegistries.ITEM, "*"));
        }
        return store;
    }

    /** A crop at an age: its own "age" property, whatever its range. */
    @SuppressWarnings("unchecked")
    private static BlockState aged(Block crop, int age) {
        BlockState state = crop.defaultBlockState();
        for (Property<?> property : state.getProperties()) {
            if (property.getName().equals("age") && property instanceof IntegerProperty ages) {
                return state.setValue(ages, Math.min(age, ages.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(age)));
            }
        }
        return state;
    }

    /**
     * Mystical Agriculture's inferium crop with a Block Sensor: found ripe by its state, harvested
     * (its essence and seeds into storage), replanted from storage, and the watch sees it.
     */
    private static void moddedCrops(GameTestHelper helper) {
        Block crop = block(helper, "mysticalagriculture:inferium_crop");
        Item seeds = item(helper, "mysticalagriculture:inferium_seeds");
        Item essence = item(helper, "mysticalagriculture:inferium_essence");
        ServerComputer computer = QboticsGameTests.startAt(helper, new BlockPos(-1, 1, 0));
        InterfaceBlockEntity store = store(helper, new BlockPos(-1, 2, 0));
        store.setItem(0, new ItemStack(seeds, 2));
        helper.setBlock(new BlockPos(-1, 1, 1), QboticsContent.BLOCK_SENSOR_BLOCK.get());
        helper.getBlockEntity(new BlockPos(-1, 1, 1), qbotics.block.BlockSensorBlockEntity.class).onPlaced(UUID.randomUUID());
        BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.MOISTURE, 7);
        helper.setBlock(new BlockPos(0, 1, 1), farmland);
        helper.setBlock(new BlockPos(0, 2, 1), aged(crop, 7));
        for (String line : List.of(
                "python",
                "from logistics import network",
                "net = network()",
                "s = net.block_sensor()",
                "store = net.interface()",
                "s.set_area(x=1, y=1, z=0)",
                "w = s.watch('mysticalagriculture:inferium_crop')",
                "ripe = s.blocks('mysticalagriculture:inferium_crop age=7')",
                "print('ripe', len(ripe), ripe[0]['pos'])",
                "got = s.break_block(ripe[0]['pos'], to=store)",
                "print('got', sorted(got))",
                "print('planted', s.place(ripe[0]['pos'], 'mysticalagriculture:inferium_seeds', store))",
                "print('young', s.block((1, 1, 0))['state']['age'])",
                "print('seen', [c['block'] for c in w.changes()])")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            QboticsGameTests.assertScreen(helper, computer, "ripe 1 [1, 1, 0]\n", "planted True\n", "young 0\n",
                    "seen ['mysticalagriculture:inferium_crop']\n");
            helper.assertTrue(store.countItem(essence) >= 1, "the harvest's inferium essence should be in storage");
        });
    }

    /**
     * Animal Garden's crocodile with an Entity Sensor: listed by its mod, read, its hands and
     * armour reachable as slots, and hit with a sword from storage.
     */
    private static void moddedCreature(GameTestHelper helper) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(Identifier.parse("animalgarden_crocodile:crocodile"));
        helper.assertTrue(type != EntityType.PIG, "Animal Garden's crocodile should be loaded");
        ServerComputer computer = QboticsGameTests.startAt(helper, new BlockPos(-1, 1, 0));
        InterfaceBlockEntity store = store(helper, new BlockPos(-1, 2, 0));
        store.setItem(0, new ItemStack(Items.IRON_SWORD));
        helper.setBlock(new BlockPos(-1, 1, 1), QboticsContent.ENTITY_SENSOR_BLOCK.get());
        var sensor = helper.getBlockEntity(new BlockPos(-1, 1, 1), qbotics.block.EntitySensorBlockEntity.class);
        sensor.onPlaced(UUID.randomUUID());
        sensor.setArea(3, 2);
        Entity crocodile = helper.spawn(type, new BlockPos(1, 1, 1));
        if (crocodile instanceof net.minecraft.world.entity.Mob mob) {
            mob.setNoAi(true);
        }
        for (String line : List.of(
                "python",
                "from logistics import network",
                "net = network()",
                "s = net.entity_sensor()",
                "store = net.interface()",
                "c = s.entities('@animalgarden')[0]",
                "print('seen', c.type, c.health > 0, c.slots, c.hostile)",
                "before = c.health",
                "r = c.attack('iron_sword', store)",
                "print('hit', r['health'] < before, store.count('iron_sword'))")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> QboticsGameTests.assertScreen(helper, computer, "seen animalgarden_crocodile:crocodile True 6 ",
                "hit True 1\n"));
    }
}
