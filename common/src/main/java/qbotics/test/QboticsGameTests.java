package qbotics.test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import qbotics.QboticsContent;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CableBlock;
import qbotics.block.CablePort;
import qbotics.block.ComputerBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.network.NetworkDevice.Kind;
import qbotics.block.PortBlockEntity;
import qbotics.network.AccessFace;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;
import qbotics.network.RadioNetwork;
import qbotics.computer.ComputerManager;
import qbotics.computer.ServerComputer;
import qbotics.computer.Terminal;
import qbotics.net.TerminalInputPayload;
import qbotics.net.TerminalStatePayload;

/**
 * In-game tests, run by each loader's game test server (NeoForge: gradlew :neoforge:runGameTestServer).
 * They use only Minecraft's own test API, so every loader can register the same functions.
 */
public final class QboticsGameTests {
    private static final BlockPos POS = new BlockPos(1, 1, 1);

    /** Test name -> test body. Each runs in the "qbotics:empty" structure. */
    public static final Map<String, Consumer<GameTestHelper>> ALL = Map.ofEntries(
            Map.entry("cable_recipes", QboticsGameTests::cableRecipes),
            Map.entry("cable_connects", QboticsGameTests::cableConnects),
            Map.entry("computer_repl", QboticsGameTests::computerRepl),
            Map.entry("computer_interrupt", QboticsGameTests::computerInterrupt),
            Map.entry("computer_recipe", QboticsGameTests::computerRecipe),
            Map.entry("computer_break", QboticsGameTests::computerBreak),
            Map.entry("computer_files", QboticsGameTests::computerFiles),
            Map.entry("computer_shell", QboticsGameTests::computerShell),
            Map.entry("computer_scratchpad", QboticsGameTests::computerScratchpad),
            Map.entry("computer_input", QboticsGameTests::computerInput),
            Map.entry("network_addresses", QboticsGameTests::networkAddresses),
            Map.entry("access_faces", QboticsGameTests::accessFaces),
            Map.entry("interface_filters", QboticsGameTests::interfaceFilters),
            Map.entry("logistics_transfer", QboticsGameTests::logisticsTransfer),
            Map.entry("cable_plates", QboticsGameTests::cablePlates),
            Map.entry("network_probe", QboticsGameTests::networkProbe),
            Map.entry("antenna_addresses", QboticsGameTests::antennaAddresses),
            Map.entry("radio_messages", QboticsGameTests::radioMessages),
            Map.entry("payload_codecs", QboticsGameTests::payloadCodecs));

    private QboticsGameTests() {
    }

    private static ServerComputer placeAndStart(GameTestHelper helper) {
        helper.setBlock(POS, QboticsContent.COMPUTER_BLOCK.get());
        ComputerBlockEntity block = helper.getBlockEntity(POS, ComputerBlockEntity.class);
        return ComputerManager.get(helper.getLevel().getServer()).getOrStart(block);
    }

    /** Everything on the terminal, including what has scrolled off the top. */
    private static String screen(ServerComputer computer) {
        Terminal.State state = computer.terminal().full();
        List<String> all = new ArrayList<>(state.scrolled());
        all.addAll(state.lines());
        return String.join("\n", all);
    }

    private static void assertScreen(GameTestHelper helper, ServerComputer computer, String... expected) {
        String screen = screen(computer);
        for (String text : expected) {
            helper.assertTrue(screen.contains(text), "screen should contain '" + text + "' but was:\n" + screen);
        }
    }

    /** Typing at the prompt: assignment, expression echo, a multi-line loop and an error. */
    private static void computerRepl(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "x = 6", "x * 7", "for i in range(3):", "    print('row', i * 100)", "", "1 / 0", "print('end')")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer,
                "42\n>>> for i in range(3):\n", "...     print('row', i * 100)\n...\nrow 0\nrow 100\nrow 200\n",
                "ZeroDivisionError: divide by zero", "\nend\n>>>"));
    }

    /** Ctrl+C stops an endless loop and the prompt comes back. */
    private static void computerInterrupt(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "while True:", "    pass", "")) {
            computer.submitLine(line);
        }
        // Well after the loop has started, even with other tests competing for the two CPU permits.
        helper.runAfterDelay(60, computer::interrupt);
        helper.runAfterDelay(65, () -> computer.submitLine("print('still', 'alive')"));
        helper.succeedWhen(() -> assertScreen(helper, computer, "program stopped by the computer", "still alive\n>>>"));
    }

    /** What the 3x3 crafting grid gives for these nine stacks, row by row. */
    private static ItemStack craft(GameTestHelper helper, ItemStack... grid) {
        ServerLevel level = helper.getLevel();
        CraftingInput input = CraftingInput.of(3, 3, List.of(grid));
        return level.getServer().getRecipeManager()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.value().assemble(input))
                .orElse(ItemStack.EMPTY);
    }

    /** Cable in the shape of leggings, redstone in the middle, glass at the bottom middle. */
    private static void computerRecipe(GameTestHelper helper) {
        ItemStack c = new ItemStack(QboticsContent.CABLE_BLOCK.get());
        ItemStack result = craft(helper,
                c, c, c,
                c, new ItemStack(Items.REDSTONE), c,
                c, new ItemStack(Blocks.GLASS), c);
        helper.assertTrue(result.is(QboticsContent.COMPUTER_BLOCK.get().asItem()) && result.getCount() == 1,
                "recipe should give one computer but gave " + result);
        helper.succeed();
    }

    /** Six iron and three redstone give 16 cables, as vertical or as horizontal lines. */
    private static void cableRecipes(GameTestHelper helper) {
        ItemStack i = new ItemStack(Items.IRON_INGOT);
        ItemStack r = new ItemStack(Items.REDSTONE);
        ItemStack vertical = craft(helper, i, r, i, i, r, i, i, r, i);
        ItemStack horizontal = craft(helper, i, i, i, r, r, r, i, i, i);
        for (ItemStack result : List.of(vertical, horizontal)) {
            helper.assertTrue(result.is(QboticsContent.CABLE_BLOCK.get().asItem()) && result.getCount() == 16,
                    "recipe should give 16 cables but gave " + result);
        }
        helper.succeed();
    }

    /** A cable reaches out to cables and computers beside it, and to nothing else. */
    private static void cableConnects(GameTestHelper helper) {
        CableBlock cable = QboticsContent.CABLE_BLOCK.get();
        BlockPos middle = new BlockPos(1, 1, 1);
        helper.setBlock(middle, cable);
        helper.setBlock(middle.east(), cable);
        helper.setBlock(middle.above(), QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(middle.north(), Blocks.STONE);
        // Blocks set by a test don't run placement logic, so ask for the connected state.
        BlockState state = CableBlock.withConnections(helper.getLevel(), helper.absolutePos(middle), cable.defaultBlockState());
        helper.assertTrue(arm(state, Direction.EAST) && arm(state, Direction.UP)
                && !arm(state, Direction.NORTH) && !arm(state, Direction.WEST)
                && !arm(state, Direction.SOUTH) && !arm(state, Direction.DOWN),
                "cable should connect east (cable) and up (computer) only, but was " + state);
        helper.succeed();
    }

    /** Breaking the block drops it and shuts its computer down. */
    private static void computerBreak(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        UUID deviceId = computer.deviceId();
        ComputerManager manager = ComputerManager.get(helper.getLevel().getServer());
        helper.assertTrue(manager.isRunning(deviceId), "computer should be running after start");
        helper.getLevel().destroyBlock(helper.absolutePos(POS), true);
        helper.assertTrue(!manager.isRunning(deviceId), "computer should stop when its block is broken");
        helper.succeedWhen(() -> helper.assertItemEntityPresent(QboticsContent.COMPUTER_BLOCK.get().asItem()));
    }

    /**
     * Files written in Python are real files in the world folder, programs can be imported
     * and run from them, and the dropped item keeps the drive so the files aren't lost.
     */
    private static void computerFiles(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        ComputerManager manager = ComputerManager.get(helper.getLevel().getServer());
        java.nio.file.Path folder = manager.driveFolder(computer.driveId());
        for (String line : List.of(
                "python",
                "f = open('greet.py', 'w')",
                "f.write('def hello(name):\\n    return \"hi \" + name\\n')",
                "f.close()",
                "import os, greet",
                "print(os.listdir(), greet.hello('qbot'))",
                "open('boom.py', 'w').write('x = 1\\ny = x / 0\\n')",
                "execfile('boom.py')")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "['greet.py'] hi qbot", "File \"boom.py\", line 2", "ZeroDivisionError");
            helper.assertTrue(java.nio.file.Files.isRegularFile(folder.resolve("greet.py")),
                    "greet.py should be a real file in " + folder);
            // Break the block: the item that drops must carry the drive.
            UUID driveId = computer.driveId();
            helper.getLevel().destroyBlock(helper.absolutePos(POS), true);
            boolean carried = helper.getLevel()
                    .getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(helper.absolutePos(POS)).inflate(3))
                    .stream().anyMatch(item -> driveId.equals(item.getItem().get(QboticsContent.DRIVE_ID.get())));
            helper.assertTrue(carried, "the dropped computer should carry drive " + driveId);
        });
    }

    /** The shell: running a program, copying, listing, and plain-worded mistakes. */
    private static void computerShell(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of(
                "python",
                "open('hi.py', 'w').write('print(\"hi from file\", 6 * 7)\\n')",
                "exit()",
                "run hi.py",
                "mkdir lib",
                "cp hi.py lib",
                "cd lib",
                "ls",
                "cat nope.py",
                "frobnicate")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> assertScreen(helper, computer,
                "/ $ run hi.py\nhi from file 42\n", "/lib $ ls\nhi.py", "no such file or folder",
                "Unknown command: frobnicate", "\n/lib $"));
    }

    /** A scratchpad run happens on the computer, shares its Python variables, and leaves the terminal alone. */
    private static void computerScratchpad(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        computer.submitScratch(UUID.randomUUID(), 1, "shared = 41 + 1\nprint('this goes to the scratchpad, not the terminal')");
        computer.submitLine("python");
        computer.submitLine("shared");
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, ">>> shared\n42\n");
            helper.assertTrue(!screen(computer).contains("scratchpad, not"), "scratchpad output leaked into the terminal:\n" + screen(computer));
        });
    }

    /** input() takes the next line typed at the terminal; Ctrl+C gets a waiting program out of it. */
    private static void computerInput(GameTestHelper helper) {
        ServerComputer computer = placeAndStart(helper);
        for (String line : List.of("python", "name = input('Name? ')", "Ada", "print('hi', name)", "input('again? ')")) {
            computer.submitLine(line);
        }
        helper.runAfterDelay(40, computer::interrupt);
        helper.runAfterDelay(45, () -> computer.submitLine("print('after', len(name))"));
        // A scratchpad run has no terminal: input() there is an error, not a hang.
        helper.runAfterDelay(50, () -> computer.submitScratch(UUID.randomUUID(), 1, "input('never asked')"));
        helper.runAfterDelay(55, () -> computer.submitLine("print('still here')"));
        helper.succeedWhen(() -> assertScreen(helper, computer,
                "Name? Ada\n>>> print('hi', name)\nhi Ada\n", "again?\n", "KeyboardInterrupt", "after 3\n", "still here\n>>>"));
    }

    /** Both packets survive being written to and read from a buffer. */
    private static void payloadCodecs(GameTestHelper helper) {
        Terminal terminal = new Terminal();
        for (int i = 0; i < 40; i++) {
            terminal.write("line " + i + "\n");
        }
        terminal.write(">>> ");
        // 40 lines and a prompt on a 14-row screen: 27 lines have scrolled off.
        Terminal.State first = terminal.takeUpdate();
        terminal.write("one more\n");
        Terminal.State second = terminal.takeUpdate();
        helper.assertTrue(first.scrolled().size() == 27 && first.scrolledStart() == 0 && first.scrolled().get(26).equals("line 26")
                && second.scrolled().equals(List.of("line 27")) && second.scrolledStart() == 27
                && terminal.full().scrolled().size() == 28, "scroll-back should be handed out once, in order: " + second);

        TerminalStatePayload state = new TerminalStatePayload(UUID.randomUUID(), true, terminal.full());
        ByteBuf buffer = Unpooled.buffer();
        TerminalStatePayload.STREAM_CODEC.encode(buffer, state);
        TerminalStatePayload stateBack = TerminalStatePayload.STREAM_CODEC.decode(buffer);
        helper.assertTrue(state.deviceId().equals(stateBack.deviceId()) && stateBack.open()
                && state.state().equals(stateBack.state()), "terminal state changed in transit: " + stateBack);

        TerminalInputPayload input = new TerminalInputPayload(UUID.randomUUID(), TerminalInputPayload.LINE, "print('héllo')");
        buffer.clear();
        TerminalInputPayload.STREAM_CODEC.encode(buffer, input);
        helper.assertTrue(input.equals(TerminalInputPayload.STREAM_CODEC.decode(buffer)), "terminal input changed in transit");
        helper.succeed();
    }

    // ---- cable networks and the item blocks (design doc §9.3, §11.0) -----------------

    private static ServerComputer startAt(GameTestHelper helper, BlockPos pos) {
        helper.setBlock(pos, QboticsContent.COMPUTER_BLOCK.get());
        return ComputerManager.get(helper.getLevel().getServer()).getOrStart(helper.getBlockEntity(pos, ComputerBlockEntity.class));
    }

    /** True if a cable reaches out to its neighbour on this side. */
    private static boolean arm(BlockState cable, Direction side) {
        return CableBlock.side(cable, side) == CableBlock.Side.CABLE;
    }

    private static BlockState port(Kind kind, Direction pointing) {
        return QboticsContent.CABLE_BLOCK.get().withPlate(kind, pointing);
    }

    private static CableNetwork networkAt(GameTestHelper helper, BlockPos pos) {
        return CableNetwork.at(helper.getLevel(), helper.absolutePos(pos));
    }

    private static List<String> ips(CableNetwork network) {
        return network.devices().stream().map(NetworkDevice::ip).toList();
    }

    /**
     * Devices joined by cable (or touching) form one network, each with its own internal IP
     * and a hardware address. Joining two networks keeps addresses where it can, and cutting
     * the cable parts them again.
     */
    private static void networkAddresses(GameTestHelper helper) {
        BlockPos computerPos = new BlockPos(0, 1, 0);
        BlockPos farInput = new BlockPos(0, 1, 2);
        BlockPos link = new BlockPos(0, 1, 1);
        ServerComputer computer = startAt(helper, computerPos);
        helper.setBlock(new BlockPos(1, 1, 0), QboticsContent.CABLE_BLOCK.get());
        helper.setBlock(new BlockPos(2, 1, 0), port(Kind.BUS, Direction.SOUTH));
        helper.setBlock(new BlockPos(1, 1, 1), QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(farInput, QboticsContent.INTERFACE_BLOCK.get());

        CableNetwork network = networkAt(helper, computerPos);
        helper.assertTrue(ips(network).equals(List.of("192.168.1.2", "192.168.1.3", "192.168.1.4")),
                "three devices should have the first three addresses, but had " + ips(network));
        for (NetworkDevice device : network.devices()) {
            helper.assertTrue(device.mac().matches("02(:[0-9A-F]{2}){5}"), "odd hardware address " + device.mac());
        }
        helper.assertTrue(network.devices().stream().map(NetworkDevice::mac).distinct().count() == 3, "hardware addresses should differ");
        helper.assertTrue(ips(networkAt(helper, farInput)).equals(List.of("192.168.1.2")),
                "an input on its own is a network of one, but was " + ips(networkAt(helper, farInput)));

        // The bus doesn't join the network through the side that points at its target.
        CablePort bus = helper.getBlockEntity(new BlockPos(2, 1, 0), PortBlockEntity.class).port(Direction.SOUTH);
        helper.assertTrue(bus != null && bus.kind() == Kind.BUS, "the cable should carry a bus on its south side");
        BlockState cable = CableBlock.withConnections(helper.getLevel(), helper.absolutePos(new BlockPos(2, 1, 1)),
                QboticsContent.CABLE_BLOCK.get().defaultBlockState());
        helper.assertTrue(!arm(cable, Direction.NORTH), "a cable in front of a bus should not reach out to it");

        // Join the far input on: it held .2 as well, so one of the two is given a new address.
        Map<String, String> before = new java.util.HashMap<>();
        network.devices().forEach(device -> before.put(device.mac(), device.ip()));
        helper.setBlock(link, QboticsContent.CABLE_BLOCK.get());
        CableNetwork joined = networkAt(helper, computerPos);
        helper.assertTrue(joined.devices().size() == 4 && ips(joined).stream().distinct().count() == 4,
                "four devices should have four different addresses, but had " + ips(joined));
        long kept = joined.devices().stream().filter(device -> device.ip().equals(before.get(device.mac()))).count();
        helper.assertTrue(kept >= 2, "joining should leave most addresses alone, but only " + kept + " of 3 stayed");

        helper.setBlock(link, Blocks.AIR);
        helper.assertTrue(networkAt(helper, computerPos).devices().size() == 3, "cutting the cable should part the networks");

        // Names: cleaned up, and usable to find the device.
        bus.setDeviceName("smelter bus!");
        helper.assertTrue(bus.deviceName().equals("smelter_bus") && networkAt(helper, computerPos).find("smelter_bus").size() == 1,
                "name should be cleaned to smelter_bus but was " + bus.deviceName());

        computer.submitLine("devices");
        helper.succeedWhen(() -> assertScreen(helper, computer, "IP address", "bus       smelter_bus", "(this computer)"));
    }

    /** Which real side each face means, for a bus looking sideways and one looking down. */
    private static void accessFaces(GameTestHelper helper) {
        Direction south = Direction.SOUTH;
        helper.assertTrue(AccessFace.NEAR.resolve(south) == Direction.NORTH && AccessFace.FAR.resolve(south) == Direction.SOUTH
                && AccessFace.TOP.resolve(south) == Direction.UP && AccessFace.BOTTOM.resolve(south) == Direction.DOWN
                && AccessFace.LEFT.resolve(south) == Direction.EAST && AccessFace.RIGHT.resolve(south) == Direction.WEST,
                "faces for a bus looking south are wrong");
        Direction down = Direction.DOWN;
        helper.assertTrue(AccessFace.NEAR.resolve(down) == Direction.UP && AccessFace.FAR.resolve(down) == Direction.DOWN
                && AccessFace.TOP.resolve(down) == Direction.NORTH && AccessFace.LEFT.resolve(down) == Direction.WEST,
                "faces for a bus looking down are wrong");
        helper.assertTrue(AccessFace.parse("top") == AccessFace.TOP && AccessFace.parse("sideways") == null, "face words");
        helper.succeed();
    }

    /** An input slot takes only its filter's item, one stack of it; a hopper obeys the same rule. */
    private static void interfaceFilters(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, QboticsContent.INTERFACE_BLOCK.get());
        InterfaceBlockEntity input = helper.getBlockEntity(pos, InterfaceBlockEntity.class);
        ItemStack coal = new ItemStack(Items.COAL);
        helper.assertTrue(!input.canPlaceItem(0, coal) && input.insert(coal, 10, false) == 0, "every slot should start closed");
        input.setFilter(0, Items.COAL);
        input.setFilter(4, Items.COAL);
        helper.assertTrue(input.canPlaceItem(0, coal) && !input.canPlaceItem(1, coal) && !input.canPlaceItem(0, new ItemStack(Items.STONE)),
                "only slots 0 and 4 should take coal");
        helper.assertTrue(input.insert(coal, 100, true) == 100 && input.isEmpty(), "a trial run should change nothing");
        helper.assertTrue(input.insert(coal, 200, false) == 128 && input.getItem(0).getCount() == 64 && input.getItem(4).getCount() == 64,
                "two open slots hold two stacks");
        helper.assertTrue(input.extract(coal, 70, false) == 70 && input.countItem(Items.COAL) == 58, "70 of 128 taken leaves 58");

        // By hand: clicking the screen's top row with an item sets the filter of the slot below it.
        BlockPos handPos = new BlockPos(3, 1, 3);
        helper.setBlock(handPos, QboticsContent.INTERFACE_BLOCK.get());
        InterfaceBlockEntity byHand = helper.getBlockEntity(handPos, InterfaceBlockEntity.class);
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.inventory.AbstractContainerMenu menu = byHand.createMenu(1, player.getInventory(), player);
        net.minecraft.world.inventory.ContainerInput click = net.minecraft.world.inventory.ContainerInput.PICKUP;
        menu.setCarried(new ItemStack(Items.COAL, 20));
        menu.clicked(9 + 2, 0, click, player);
        helper.assertTrue(byHand.isEmpty() && menu.getCarried().getCount() == 20, "a closed slot should refuse the coal");
        menu.clicked(2, 0, click, player);
        helper.assertTrue(byHand.filter(2) == Items.COAL && menu.getCarried().getCount() == 20 && menu.getSlot(2).getItem().is(Items.COAL),
                "clicking the top row should set the filter, show it, and leave the coal in hand");
        menu.clicked(9 + 2, 0, click, player);
        helper.assertTrue(byHand.getItem(2).getCount() == 20 && menu.getCarried().isEmpty(), "the open slot should now take the coal");
        menu.clicked(2, 0, click, player);
        helper.assertTrue(byHand.filter(2) == null && menu.getSlot(2).getItem().isEmpty() && byHand.getItem(2).getCount() == 20,
                "an empty hand on the top row should close the slot and leave its items");
        menu.quickMoveStack(player, 2);
        helper.assertTrue(byHand.getItem(2).getCount() == 20, "shift-clicking a filter should move nothing");
        menu.removed(player);

        // A hopper above feeds it coal but can't push in the iron.
        helper.setBlock(pos.above(), Blocks.HOPPER);
        net.minecraft.world.level.block.entity.HopperBlockEntity hopper =
                helper.getBlockEntity(pos.above(), net.minecraft.world.level.block.entity.HopperBlockEntity.class);
        hopper.setItem(0, new ItemStack(Items.IRON_INGOT, 3));
        hopper.setItem(1, new ItemStack(Items.COAL, 5));
        helper.succeedWhen(() -> helper.assertTrue(input.countItem(Items.COAL) == 63 && hopper.countItem(Items.IRON_INGOT) == 3,
                "the hopper should have delivered 5 coal and kept its iron; input has " + input.countItem(Items.COAL) + " coal"));
    }

    /**
     * A program moves items: from an input through a bus into a furnace's ingredient and fuel
     * slots, the furnace's product back out, and the rest through an output into a barrel.
     */
    private static void logisticsTransfer(GameTestHelper helper) {
        BlockPos inputPos = new BlockPos(1, 1, 0);
        BlockPos furnacePos = new BlockPos(2, 1, 1);
        BlockPos barrelPos = new BlockPos(1, 2, 1);
        ServerComputer computer = startAt(helper, new BlockPos(0, 1, 0));
        helper.setBlock(inputPos, QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(furnacePos, Blocks.FURNACE);
        helper.setBlock(new BlockPos(2, 1, 0), port(Kind.BUS, Direction.SOUTH));
        helper.setBlock(barrelPos, Blocks.BARREL);
        helper.setBlock(new BlockPos(1, 1, 1), port(Kind.OUTPUT, Direction.UP));

        InterfaceBlockEntity input = helper.getBlockEntity(inputPos, InterfaceBlockEntity.class);
        input.setFilter(0, Items.RAW_IRON);
        input.setItem(0, new ItemStack(Items.RAW_IRON, 16));
        input.setFilter(1, Items.COAL);
        input.setItem(1, new ItemStack(Items.COAL, 8));
        net.minecraft.world.Container furnace = helper.getBlockEntity(furnacePos, net.minecraft.world.level.block.entity.FurnaceBlockEntity.class);
        furnace.setItem(2, new ItemStack(Items.IRON_INGOT, 3));
        net.minecraft.world.Container barrel = helper.getBlockEntity(barrelPos, net.minecraft.world.level.block.entity.BarrelBlockEntity.class);

        for (String line : List.of(
                "python",
                "from logistics import network, Face",
                "net = network()",
                "f = net.bus()",
                "inbox = net.interface()",
                "print('before', f.count('iron_ingot'), inbox.filters()[2])",
                "inbox.set_slot_filter(2, 'minecraft:iron_ingot')",
                "a = f.insert('minecraft:raw_iron', 5, face=Face.TOP)",
                "b = f.insert('coal', 2, source=inbox, face=Face.LEFT)",
                "print('moved', a, b, f.extract('iron_ingot', 64, face=Face.BOTTOM))",
                "print('out', net.output().insert('raw_iron', 100), inbox.count('raw_iron'), inbox.count('iron_ingot'))",
                "print(len(net.devices()), net.me.kind, f.target, f.ip.startswith('192.168.1.'))",
                "net.bus('nope')",
                "f.insert('minecraft:not_a_thing')",
                "net.output().extract",
                "import network",
                "print('host', network.hostname('base'), network.ifconfig()['ip'] == net.me.ip)")) {
            computer.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, computer, "before 3 None\n", "moved 5 2 3\n", "out 11 0 3\n", "4 computer minecraft:furnace True\n",
                    "NetworkError: No bus on this network has the name or address 'nope'.",
                    "There is no item called 'minecraft:not_a_thing'", "AttributeError", "host base True\n");
            helper.assertTrue(furnace.getItem(0).is(Items.RAW_IRON) && furnace.getItem(2).isEmpty(),
                    "the furnace should hold raw iron and no ingots: " + furnace.getItem(0) + " / " + furnace.getItem(2));
            helper.assertTrue(barrel.countItem(Items.RAW_IRON) == 11, "the barrel should hold 11 raw iron but holds " + barrel.countItem(Items.RAW_IRON));
            helper.assertTrue(input.getItem(1).getCount() == 6 && input.getItem(2).is(Items.IRON_INGOT),
                    "the input should have 6 coal left and the ingots in slot 2");
        });
    }

    /**
     * Plates share a cable's block: one is attached by clicking the block it should work on,
     * points at that block, is a device on the network, and comes off again as an item.
     */
    private static void cablePlates(GameTestHelper helper) {
        BlockPos cablePos = new BlockPos(1, 1, 1);
        BlockPos chestPos = new BlockPos(1, 1, 2);
        BlockPos computerPos = new BlockPos(0, 1, 1);
        BlockPos storePos = new BlockPos(1, 1, 0);
        ServerLevel level = helper.getLevel();
        helper.setBlock(cablePos, QboticsContent.CABLE_BLOCK.get());
        helper.setBlock(chestPos, Blocks.CHEST);
        helper.setBlock(computerPos, QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(storePos, QboticsContent.INTERFACE_BLOCK.get());
        net.minecraft.world.Container chest = helper.getBlockEntity(chestPos, net.minecraft.world.level.block.entity.ChestBlockEntity.class);
        chest.setItem(5, new ItemStack(Items.COAL, 12));
        InterfaceBlockEntity store = helper.getBlockEntity(storePos, InterfaceBlockEntity.class);
        store.setFilter(0, Items.COAL);

        // A player holding an Input clicks the chest's north face: the plate goes on the cable in front of it.
        net.minecraft.world.entity.player.Player player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        net.minecraft.world.InteractionHand hand = net.minecraft.world.InteractionHand.MAIN_HAND;
        ItemStack inputs = new ItemStack(QboticsContent.PLATE_ITEMS.get(Kind.INPUT).get(), 2);
        inputs.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("ore in"));
        player.setItemInHand(hand, inputs);
        java.util.function.BiFunction<BlockPos, Direction, net.minecraft.world.InteractionResult> click = (pos, face) -> {
            BlockPos at = helper.absolutePos(pos);
            return player.getItemInHand(hand).useOn(new net.minecraft.world.item.context.UseOnContext(player, hand,
                    new net.minecraft.world.phys.BlockHitResult(at.getCenter(), face, at, false)));
        };
        click.apply(chestPos, Direction.NORTH);
        BlockState withPlate = helper.getBlockState(cablePos);
        helper.assertTrue(CableBlock.plateKind(withPlate, Direction.SOUTH) == Kind.INPUT && arm(withPlate, Direction.WEST)
                && !arm(withPlate, Direction.SOUTH) && player.getItemInHand(hand).getCount() == 1,
                "the cable should carry an input pointing south at the chest, and one item be used: " + withPlate);
        PortBlockEntity host = helper.getBlockEntity(cablePos, PortBlockEntity.class);
        CablePort plate = host.port(Direction.SOUTH);
        helper.assertTrue(plate.kind() == Kind.INPUT && plate.targetPos().equals(helper.absolutePos(chestPos)) && plate.deviceName().equals("ore_in"),
                "the plate should be an input named ore_in pointing at the chest, but is " + plate.kind() + " " + plate.deviceName());
        click.apply(chestPos, Direction.NORTH);
        helper.assertTrue(player.getItemInHand(hand).getCount() == 1, "a side carries only one plate");

        // A second plate goes on another side of the same cable: here, pointing up at a barrel.
        helper.setBlock(cablePos.above(), Blocks.BARREL);
        click.apply(cablePos.above(), Direction.DOWN);
        BlockState withTwo = helper.getBlockState(cablePos);
        CablePort second = host.port(Direction.UP);
        helper.assertTrue(CableBlock.plateKind(withTwo, Direction.UP) == Kind.INPUT && CableBlock.plateKind(withTwo, Direction.SOUTH) == Kind.INPUT
                && second != null && host.ports().size() == 2 && !second.mac().equals(plate.mac())
                && second.targetPos().equals(helper.absolutePos(cablePos.above())) && player.getItemInHand(hand).isEmpty(),
                "the cable should carry two inputs, each its own device: " + withTwo);

        // With no cable in front of the clicked face, one is taken from the player's inventory.
        player.setItemInHand(hand, new ItemStack(QboticsContent.PLATE_ITEMS.get(Kind.BUS).get()));
        click.apply(chestPos, Direction.UP);
        helper.assertTrue(helper.getBlockState(chestPos.above()).isAir() && player.getItemInHand(hand).getCount() == 1, "no cable, no plate");
        player.getInventory().add(new ItemStack(QboticsContent.CABLE_BLOCK.get(), 3));
        click.apply(chestPos, Direction.UP);
        helper.assertTrue(CableBlock.plateKind(helper.getBlockState(chestPos.above()), Direction.DOWN) == Kind.BUS && player.getItemInHand(hand).isEmpty()
                && player.getInventory().countItem(QboticsContent.CABLE_BLOCK.get().asItem()) == 2,
                "the bus should have been placed with one of the player's cables");

        // The plates are devices on the network: the computer, the interface and the two inputs.
        BlockPos computerAt = helper.absolutePos(computerPos);
        CableNetwork network = networkAt(helper, computerPos);
        helper.assertTrue(network.devices().size() == 4 && network.devices().contains(plate) && network.devices().contains(second)
                && ips(network).stream().distinct().count() == 4, "four devices with four addresses, but had " + ips(network));
        java.util.function.Function<String, String> call = request ->
                qbotics.network.NetworkCalls.handle(level.getServer(), level.dimension(), computerAt, request);
        String mac = plate.mac();
        helper.assertTrue(call.apply("[\"contents\",\"" + mac + "\",null]").contains("\"count\":12"), "an input reads its target");
        helper.assertTrue(call.apply("[\"extract\",\"" + mac + "\",\"minecraft:coal\",5,null,null]").equals("{\"r\":5}")
                && store.getItem(0).getCount() == 5 && chest.getItem(5).getCount() == 7, "an input should move 5 coal to the interface");
        helper.assertTrue(call.apply("[\"insert\",\"" + mac + "\",\"minecraft:coal\",1,null,null]").contains("An input can only take items out"),
                "an input should refuse to put items in");
        helper.assertTrue(call.apply("[\"find\",\"" + second.mac() + "\",null]").contains("minecraft:barrel"), "the second input points at the barrel");

        // Breaking the cable drops every plate; taking plates off one at a time ends with a plain cable.
        List<ItemStack> drops = net.minecraft.world.level.block.Block.getDrops(withTwo, level, helper.absolutePos(cablePos), host);
        helper.assertTrue(drops.size() == 3 && drops.stream().anyMatch(stack -> stack.getItem() instanceof qbotics.block.PlateItem
                && stack.getHoverName().getString().equals("ore_in")), "the drops should be the cable and both plates: " + drops);
        ItemStack taken = CableBlock.detach(level, helper.absolutePos(cablePos), Direction.SOUTH);
        helper.assertTrue(taken.getItem() instanceof qbotics.block.PlateItem && plate.isRemoved() && !second.isRemoved()
                && CableBlock.plateKind(helper.getBlockState(cablePos), Direction.UP) == Kind.INPUT
                && networkAt(helper, computerPos).devices().size() == 3, "taking one plate off should leave the other working");
        // Breaking while aiming at a plate takes off only that plate. Here a player stands west of a cable and looks east at it.
        BlockPos alonePos = new BlockPos(2, 2, 0); // inside the test's walls, with clear air to its west
        helper.setBlock(alonePos, QboticsContent.CABLE_BLOCK.get().withPlate(Kind.OUTPUT, Direction.WEST));
        BlockPos aloneAt = helper.absolutePos(alonePos);
        player.snapTo(aloneAt.getX() - 1.5, aloneAt.getY() + 0.5 - player.getEyeHeight(), aloneAt.getZ() + 0.5, -90.0f, 0.0f);
        player.setYHeadRot(-90.0f);
        helper.assertTrue(CableBlock.plateAimedAt(helper.getBlockState(alonePos), aloneAt, player) == Direction.WEST,
                "the player should be aiming at the west plate");
        helper.assertTrue(CableBlock.breakPlateInstead(helper.getBlockState(alonePos), level, aloneAt, player)
                && helper.getBlockState(alonePos).getBlock() instanceof CableBlock && !CableBlock.hasPlates(helper.getBlockState(alonePos)),
                "breaking at the plate should leave the cable without it");
        helper.assertTrue(!CableBlock.breakPlateInstead(helper.getBlockState(alonePos), level, aloneAt, player),
                "with no plate aimed at, the cable itself breaks as usual");

        CableBlock.detach(level, helper.absolutePos(cablePos), Direction.UP);
        helper.assertTrue(!CableBlock.hasPlates(helper.getBlockState(cablePos)) && level.getBlockEntity(helper.absolutePos(cablePos)) == null
                && networkAt(helper, computerPos).devices().size() == 2, "taking the last plate off should leave a bare cable and two devices");
        helper.succeed();
    }

    /** The Network Probe reads any device's name and addresses, picks out one plate on a cable, and renames. */
    private static void networkProbe(GameTestHelper helper) {
        BlockPos computerPos = new BlockPos(0, 1, 0);
        BlockPos storePos = new BlockPos(1, 1, 0);
        BlockPos cablePos = new BlockPos(0, 1, 1);
        ServerLevel level = helper.getLevel();
        helper.setBlock(computerPos, QboticsContent.COMPUTER_BLOCK.get());
        helper.setBlock(storePos, QboticsContent.INTERFACE_BLOCK.get());
        helper.setBlock(cablePos, port(Kind.BUS, Direction.SOUTH));
        BlockPos storeAt = helper.absolutePos(storePos);
        BlockPos cableAt = helper.absolutePos(cablePos);

        qbotics.net.ProbePayloads.Info store = qbotics.block.ProbeItem.inspect(level, storeAt, storeAt.getCenter());
        helper.assertTrue(store != null && store.kind().equals("interface") && store.side() == -1 && store.ip().startsWith("192.168.1.")
                && store.mac().startsWith("02:") && store.external().isEmpty() && store.python().equals("net.interface(\"" + store.ip() + "\")"),
                "the probe should read the interface, but gave " + store);

        // Naming it changes what a program would write.
        qbotics.net.ProbePayloads.Info named = qbotics.block.ProbeItem.rename(level, null,
                new qbotics.net.ProbePayloads.Rename(storeAt, -1, "ore store!"));
        helper.assertTrue(named != null && named.name().equals("ore_store") && named.python().equals("net.interface(\"ore_store\")")
                && helper.getBlockEntity(storePos, InterfaceBlockEntity.class).deviceName().equals("ore_store"),
                "renaming from the probe should clean and keep the name, but gave " + named);

        // On a cable the click picks out a plate; the cable itself has no address.
        net.minecraft.world.phys.Vec3 corner = new net.minecraft.world.phys.Vec3(cableAt.getX(), cableAt.getY(), cableAt.getZ());
        qbotics.net.ProbePayloads.Info bus = qbotics.block.ProbeItem.inspect(level, cableAt, corner.add(0.5, 0.5, 0.95));
        helper.assertTrue(bus != null && bus.kind().equals("bus") && bus.side() == Direction.SOUTH.get3DDataValue()
                && !bus.ip().equals(store.ip()), "the probe should read the bus on the cable's south side, but gave " + bus);
        helper.assertTrue(qbotics.block.ProbeItem.inspect(level, cableAt, corner.add(0.5, 0.5, 0.5)) == null, "the cable's core is not a device");
        qbotics.block.ProbeItem.rename(level, null, new qbotics.net.ProbePayloads.Rename(cableAt, bus.side(), "smelter"));
        helper.assertTrue(helper.getBlockEntity(cablePos, PortBlockEntity.class).port(Direction.SOUTH).deviceName().equals("smelter"),
                "a plate can be named from the probe");

        // With an antenna on the network, the address other networks use is shown too.
        helper.setBlock(computerPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        qbotics.net.ProbePayloads.Info outside = qbotics.block.ProbeItem.inspect(level, storeAt, storeAt.getCenter());
        String port = String.valueOf(1000 + Integer.parseInt(outside.ip().substring(outside.ip().lastIndexOf('.') + 1)));
        helper.assertTrue(outside.external().startsWith("100.64.") && outside.external().endsWith(":" + port),
                "the external address should end in port " + port + " but was " + outside.external());

        ByteBuf buffer = Unpooled.buffer();
        qbotics.net.ProbePayloads.Info.STREAM_CODEC.encode(buffer, outside);
        helper.assertTrue(outside.equals(qbotics.net.ProbePayloads.Info.STREAM_CODEC.decode(buffer)), "probe info changed in transit");
        helper.succeed();
    }

    // ---- antennas and messages (design doc §9.1, §9.3) --------------------------------

    /** An antenna is its network's router, has an external address, and obeys the range rule. */
    private static void antennaAddresses(GameTestHelper helper) {
        BlockPos computerPos = new BlockPos(0, 1, 0);
        BlockPos otherPos = new BlockPos(2, 1, 2);
        helper.setBlock(computerPos, QboticsContent.COMPUTER_BLOCK.get());
        helper.assertTrue(ips(networkAt(helper, computerPos)).equals(List.of("192.168.1.2")), "the computer starts at .2");
        helper.setBlock(computerPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        helper.setBlock(otherPos, QboticsContent.ANTENNA_LONG_BLOCK.get());
        AntennaBlockEntity antenna = helper.getBlockEntity(computerPos.above(), AntennaBlockEntity.class);
        AntennaBlockEntity other = helper.getBlockEntity(otherPos, AntennaBlockEntity.class);

        CableNetwork network = networkAt(helper, computerPos);
        helper.assertTrue(network.gateway() == antenna && ips(network).equals(List.of("192.168.1.1", "192.168.1.2")),
                "the antenna should be the router at .1 and the computer stay at .2, but had " + ips(network));
        helper.assertTrue(antenna.externalIp().startsWith("100.64.") && other.externalIp().startsWith("100.64.")
                && !antenna.externalIp().equals(other.externalIp()),
                "each antenna needs its own external address: " + antenna.externalIp() + " and " + other.externalIp());
        RadioNetwork radio = RadioNetwork.get(helper.getLevel().getServer());
        helper.assertTrue(radio.byExternalIp(antenna.externalIp()) == antenna, "an antenna should be found by its external address");
        helper.assertTrue(antenna.tier() == RadioNetwork.Tier.SHORT && other.tier() == RadioNetwork.Tier.LONG, "tiers come from the block");

        // Frequencies: only antennas on the same one can talk.
        helper.assertTrue(RadioNetwork.linkProblem(antenna, other) == null, "two new antennas share the default frequency");
        helper.assertTrue(antenna.tune("mine ops") && antenna.frequency().equals("mine_ops") && !antenna.tune("!!"),
                "frequency should be cleaned to mine_ops but was " + antenna.frequency());
        helper.assertTrue(RadioNetwork.linkProblem(antenna, other) != null, "different frequencies should not link");
        other.tune("mine_ops");
        helper.assertTrue(RadioNetwork.linkProblem(antenna, other) == null, "the same frequency should link again");

        // A broken antenna can't be reached any more.
        String gone = other.externalIp();
        helper.getLevel().destroyBlock(helper.absolutePos(otherPos), false);
        helper.assertTrue(radio.byExternalIp(gone) == null, "a broken antenna should leave the radio network");

        // Range: the larger of the two reaches counts; height doesn't; dimensions need Infinite at both ends.
        var overworld = net.minecraft.world.level.Level.OVERWORLD;
        var nether = net.minecraft.world.level.Level.NETHER;
        BlockPos here = BlockPos.ZERO;
        RadioNetwork.Tier s = RadioNetwork.Tier.SHORT;
        RadioNetwork.Tier l = RadioNetwork.Tier.LONG;
        RadioNetwork.Tier i = RadioNetwork.Tier.INFINITE;
        helper.assertTrue(RadioNetwork.rangeProblem(s, overworld, here, s, overworld, new BlockPos(24, 200, 0)) == null
                && RadioNetwork.rangeProblem(s, overworld, here, s, overworld, new BlockPos(25, 0, 0)) != null
                && RadioNetwork.rangeProblem(s, overworld, here, l, overworld, new BlockPos(0, 0, 100)) == null
                && RadioNetwork.rangeProblem(l, overworld, here, s, overworld, new BlockPos(80, 0, 80)) != null
                && RadioNetwork.rangeProblem(s, overworld, here, i, overworld, new BlockPos(90000, 0, 0)) == null
                && RadioNetwork.rangeProblem(l, overworld, here, i, nether, here) != null
                && RadioNetwork.rangeProblem(i, overworld, here, i, nether, new BlockPos(90000, 0, 0)) == null,
                "the range rule is wrong");

        // Placeholder recipes: a column, each tier from the one before.
        ItemStack none = ItemStack.EMPTY;
        ItemStack cable = new ItemStack(QboticsContent.CABLE_BLOCK.get());
        ItemStack shortOne = craft(helper, none, new ItemStack(Items.REDSTONE_TORCH), none, none, new ItemStack(Items.IRON_INGOT), none, none, cable, none);
        ItemStack longOne = craft(helper, none, new ItemStack(Items.DIAMOND), none, none, shortOne, none, none, cable, none);
        ItemStack infinite = craft(helper, none, new ItemStack(Items.ENDER_EYE), none, none, longOne, none, none, cable, none);
        helper.assertTrue(shortOne.is(QboticsContent.ANTENNA_SHORT_BLOCK.get().asItem()) && longOne.is(QboticsContent.ANTENNA_LONG_BLOCK.get().asItem())
                && infinite.is(QboticsContent.ANTENNA_INFINITE_BLOCK.get().asItem()),
                "antenna recipes gave " + shortOne + ", " + longOne + ", " + infinite);
        helper.succeed();
    }

    /**
     * Two computers, each with its own antenna, exchange messages by external address and
     * port; a computer can message its own network without one; mistakes are explained.
     */
    private static void radioMessages(GameTestHelper helper) {
        BlockPos aPos = new BlockPos(0, 1, 0);
        BlockPos bPos = new BlockPos(2, 1, 2);
        ServerComputer a = startAt(helper, aPos);
        ServerComputer b = startAt(helper, bPos);
        helper.setBlock(aPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        helper.setBlock(bPos.above(), QboticsContent.ANTENNA_SHORT_BLOCK.get());
        networkAt(helper, aPos);
        networkAt(helper, bPos);
        String aAddress = helper.getBlockEntity(aPos.above(), AntennaBlockEntity.class).externalIp() + ":1002";
        String bAddress = helper.getBlockEntity(bPos.above(), AntennaBlockEntity.class).externalIp() + ":1002";

        for (String line : List.of(
                "python",
                "import network",
                "m = network.receive(8)",
                "print('got', m.data['ore'], m.sender)",
                "network.send(m.sender, ['thanks', 2])")) {
            b.submitLine(line);
        }
        for (String line : List.of(
                "python",
                "import network",
                "radio = network.Radio()",
                "print(network.ifconfig()['port'], radio.tier, radio.range, radio.frequency)",
                "network.send('192.168.1.2', 'to myself')",
                "print(network.receive(1).data)",
                "print(network.receive(0))",
                "network.send('" + bAddress + "', {'ore': 128})",
                "r = network.receive(8)",
                "print('reply', r.data, r.sender)",
                "print([(d['address'], d['kind']) for d in network.scan('" + bAddress.replace(":1002", "") + "')])",
                "radio.tune('other')",
                "network.send('" + bAddress + "', 1)",
                "network.send('100.64.200.1:1002', 1)",
                "network.send('192.168.1.1', 1)",
                "print('end')")) {
            a.submitLine(line);
        }
        helper.succeedWhen(() -> {
            assertScreen(helper, a, "end\n");
            assertScreen(helper, b, "got 128 " + aAddress + "\n");
            assertScreen(helper, a, "1002 short 24 1\n", "to myself\n", "None\n", "reply ['thanks', 2] " + bAddress + "\n",
                    "[('" + bAddress.replace(":1002", ":1001") + "', 'antenna'), ('" + bAddress + "', 'computer')]\n",
                    "that antenna is tuned to a different", "No antenna with the address 100.64.200.1 can be reached",
                    "Only computers receive messages; 192.168.1.1 is an antenna.", "end\n");
        });
    }
}
