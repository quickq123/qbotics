package qbotics.stage;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CableBlock;
import qbotics.block.CablePort;
import qbotics.block.ComputerBlock;
import qbotics.block.ComputerBlockEntity;
import qbotics.block.DeviceBlockEntity;
import qbotics.block.EnergyInterfaceBlockEntity;
import qbotics.block.ExperienceInterfaceBlockEntity;
import qbotics.block.FluidInterfaceBlockEntity;
import qbotics.block.InterfaceBlockEntity;
import qbotics.computer.ComputerManager;
import qbotics.computer.ServerComputer;
import qbotics.engine.HostFileSystem;
import qbotics.network.CableNetwork;
import qbotics.network.Medium;
import qbotics.network.NameMatch;
import qbotics.network.NetworkDevice.Kind;
import qbotics.network.RadioNetwork;

/**
 * A ready-made place to try the mod's features, built by the command {@code /qbotics stage}.
 * Setting up blocks and typing programs is the slow part of testing one feature; this puts
 * every kind of device in place, wired and named, with things in the containers and demo
 * programs already on the computers.
 *
 * <p>The stage is two networks. The main one has a computer, an antenna, an Interface, plates
 * on a chest, a furnace and two barrels, and a store for fluid, energy and experience, each
 * with something to draw from. The second, a few blocks away, has its own computer and
 * antenna, to try messages and remote login.
 *
 * <pre>
 *            chest furnace inbox outbox      tank       machine    crafting table
 *   main  =    bus   bus   input output tanks fbus battery ebus  xp  bus  (z = 2; plates point north)
 *                        pin   .  placer  .                                (z = 3: a pin on a lamp south of it,
 *                        lamp    (space) breaker                            z = 4: a placer and a breaker on one space)
 *                                              anvil  enchanting table        (z = 4, each worked by a bus at z = 3)
 *
 *   remote =  store  bus->chest                                           (z = 6)
 * </pre>
 */
public final class TestStage {
    /** How far the stage reaches from its corner: it clears and floors this much. */
    public static final int WIDTH = 15;
    public static final int DEPTH = 9;

    private final ServerLevel level;
    private final BlockPos corner;
    private final @Nullable UUID owner;
    private final CableBlock cable = QboticsContent.CABLE_BLOCK.get();

    private TestStage(ServerLevel level, BlockPos corner, @Nullable UUID owner) {
        this.level = level;
        this.corner = corner;
        this.owner = owner;
    }

    /**
     * Builds the stage with its corner at a position, replacing whatever is there.
     *
     * @param owner the player every device will belong to, or null for nobody
     * @return a few lines saying what was built and what to try first
     */
    public static String build(ServerLevel level, BlockPos corner, @Nullable UUID owner) {
        return new TestStage(level, corner.immutable(), owner).build();
    }

    private BlockPos at(int x, int y, int z) {
        return corner.offset(x, y, z);
    }

    private void set(int x, int y, int z, BlockState state) {
        level.setBlock(at(x, y, z), state, Block.UPDATE_ALL);
    }

    /** A block with a network address: placed, owned and named. */
    private <T> T device(int x, int y, int z, Block block, String name, Class<T> type) {
        set(x, y, z, block.defaultBlockState());
        Object entity = level.getBlockEntity(at(x, y, z));
        if (entity instanceof DeviceBlockEntity device) {
            device.onPlaced(owner);
            device.setDeviceName(name);
        }
        return type.cast(entity);
    }

    private ComputerBlockEntity computer(int x, int z, String name) {
        // Screens face south, towards where the player stands.
        set(x, 0, z, QboticsContent.COMPUTER_BLOCK.get().defaultBlockState().setValue(ComputerBlock.FACING, Direction.SOUTH));
        ComputerBlockEntity computer = (ComputerBlockEntity) level.getBlockEntity(at(x, 0, z));
        computer.onPlaced(owner);
        computer.setDeviceName(name);
        return computer;
    }

    /** A cable with one plate on its north side, pointing at whatever is placed there. */
    private void plate(int x, int z, Kind kind, Medium medium, String name) {
        set(x, 0, z, cable.defaultBlockState());
        CablePort port = CableBlock.attach(level, at(x, 0, z), kind, medium, Direction.NORTH);
        port.onPlaced(owner);
        port.setDeviceName(name);
    }

    /** A plate put on the cable already at (x, 0, z), pointing one way. */
    private void worldPlate(int x, int z, Kind kind, Direction pointing, String name) {
        CablePort port = CableBlock.attach(level, at(x, 0, z), kind, pointing);
        port.onPlaced(owner);
        port.setDeviceName(name);
    }

    private Container container(int x, int z, Block block) {
        set(x, 0, z, block.defaultBlockState());
        return (Container) level.getBlockEntity(at(x, 0, z));
    }

    private static void write(ServerComputer computer, String path, String text) {
        try {
            computer.drive().call(HostFileSystem.WRITE, path, text.getBytes(StandardCharsets.UTF_8));
        } catch (HostFileSystem.FsException e) {
            Qbotics.LOGGER.warn("Test stage: could not write {} (error {})", path, e.errno);
        }
    }

    private static void folder(ServerComputer computer, String path) {
        try {
            computer.drive().call(HostFileSystem.MKDIR, path, new byte[0]);
        } catch (HostFileSystem.FsException e) {
            // Already there from an earlier build of the stage.
        }
    }

    private String build() {
        // Clear the space and lay a floor.
        for (int x = -1; x <= WIDTH; x++) {
            for (int z = -1; z <= DEPTH; z++) {
                set(x, -1, z, Blocks.SMOOTH_STONE.defaultBlockState());
                for (int y = 0; y < 4; y++) {
                    set(x, y, z, Blocks.AIR.defaultBlockState());
                }
            }
        }

        // ---- the main network, along z = 2; what the plates point at is along z = 1 ----
        ComputerBlockEntity main = computer(1, 2, "main");
        AntennaBlockEntity mainAntenna = device(1, 1, 2, QboticsContent.ANTENNA_SHORT_BLOCK.get(), "main_antenna", AntennaBlockEntity.class);
        set(2, 0, 2, cable.defaultBlockState());
        InterfaceBlockEntity store = device(3, 0, 2, QboticsContent.INTERFACE_BLOCK.get(), "store", InterfaceBlockEntity.class);
        String[] filters = {"coal", "#minecraft:logs", "raw_iron", "iron_ingot", "*", "*", "*", "*", "*"};
        for (int slot = 0; slot < filters.length; slot++) {
            store.setFilter(slot, NameMatch.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM, filters[slot]));
        }
        store.setItem(0, new ItemStack(Items.COAL, 16));

        Container chest = container(4, 1, Blocks.CHEST);
        chest.setItem(0, new ItemStack(Items.COAL, 32));
        chest.setItem(1, new ItemStack(Blocks.OAK_LOG, 16));
        chest.setItem(2, new ItemStack(Blocks.BIRCH_LOG, 8));
        chest.setItem(3, new ItemStack(Items.RAW_IRON, 24));
        chest.setItem(4, new ItemStack(Blocks.COBBLESTONE, 64));
        chest.setItem(9, new ItemStack(Items.IRON_INGOT, 5));
        // For the anvil and the enchanting table: two worn swords, a book and lapis.
        for (int slot = 10; slot <= 11; slot++) {
            ItemStack sword = new ItemStack(Items.IRON_SWORD);
            sword.setDamageValue(150);
            chest.setItem(slot, sword);
        }
        chest.setItem(12, new ItemStack(Items.BOOK, 3));
        chest.setItem(13, new ItemStack(Items.LAPIS_LAZULI, 9));
        plate(4, 2, Kind.BUS, Medium.ITEM, "chest");

        set(5, 0, 1, Blocks.FURNACE.defaultBlockState());
        plate(5, 2, Kind.BUS, Medium.ITEM, "furnace");

        Container inbox = container(6, 1, Blocks.BARREL);
        inbox.setItem(0, new ItemStack(Items.REDSTONE, 20));
        inbox.setItem(1, new ItemStack(Items.GOLD_INGOT, 6));
        plate(6, 2, Kind.INPUT, Medium.ITEM, "inbox");
        container(7, 1, Blocks.BARREL);
        plate(7, 2, Kind.OUTPUT, Medium.ITEM, "outbox");

        device(8, 0, 2, QboticsContent.FLUID_INTERFACE_BLOCK.get(), "tanks", FluidInterfaceBlockEntity.class);
        FluidInterfaceBlockEntity bigTank = device(9, 0, 1, QboticsContent.FLUID_INTERFACE_BLOCK.get(), "", FluidInterfaceBlockEntity.class);
        bigTank.insert(Fluids.WATER, 8000, false);
        bigTank.insert(Fluids.LAVA, 2000, false);
        plate(9, 2, Kind.BUS, Medium.FLUID, "big_tank");

        device(10, 0, 2, QboticsContent.ENERGY_INTERFACE_BLOCK.get(), "battery", EnergyInterfaceBlockEntity.class);
        EnergyInterfaceBlockEntity machine = device(11, 0, 1, QboticsContent.ENERGY_INTERFACE_BLOCK.get(), "", EnergyInterfaceBlockEntity.class);
        machine.insert(1_000_000, false);
        plate(11, 2, Kind.BUS, Medium.ENERGY, "machine");

        device(12, 0, 2, QboticsContent.EXPERIENCE_INTERFACE_BLOCK.get(), "xp", ExperienceInterfaceBlockEntity.class).add(1395);

        // A crafting table's slots exist only on its screen, which this bus opens as its owner.
        set(13, 0, 1, Blocks.CRAFTING_TABLE.defaultBlockState());
        plate(13, 2, Kind.BUS, Medium.ITEM, "crafter");

        // The world plates, on cables behind the main row (z = 3 and 4): a Logic Pin on a lamp,
        // and a Placer and a Breaker facing the same empty space from two sides.
        for (int x = 5; x <= 8; x++) {
            set(x, 0, 3, cable.defaultBlockState());
        }
        set(8, 0, 4, cable.defaultBlockState());
        set(5, 0, 4, Blocks.REDSTONE_LAMP.defaultBlockState());
        worldPlate(5, 3, Kind.PIN, Direction.SOUTH, "lamp");
        worldPlate(7, 3, Kind.PLACER, Direction.SOUTH, "placer");
        worldPlate(8, 4, Kind.BREAKER, Direction.WEST, "breaker");

        // Screens that cost levels, paid from xp: a Bus on an anvil and one on an enchanting table.
        for (int x = 9; x <= 12; x++) {
            set(x, 0, 3, cable.defaultBlockState());
        }
        set(10, 0, 4, Blocks.ANVIL.defaultBlockState());
        set(12, 0, 4, Blocks.ENCHANTING_TABLE.defaultBlockState());
        worldPlate(10, 3, Kind.BUS, Direction.SOUTH, "anvil");
        worldPlate(12, 3, Kind.BUS, Direction.SOUTH, "enchanter");

        // ---- the second network, along z = 6 ----
        ComputerBlockEntity remote = computer(1, 6, "remote");
        AntennaBlockEntity remoteAntenna = device(1, 1, 6, QboticsContent.ANTENNA_SHORT_BLOCK.get(), "remote_antenna", AntennaBlockEntity.class);
        set(2, 0, 6, cable.defaultBlockState());
        InterfaceBlockEntity remoteStore = device(3, 0, 6, QboticsContent.INTERFACE_BLOCK.get(), "remote_store", InterfaceBlockEntity.class);
        for (int slot = 0; slot < InterfaceBlockEntity.SLOTS; slot++) {
            remoteStore.setFilter(slot, NameMatch.parse(net.minecraft.core.registries.BuiltInRegistries.ITEM, "*"));
        }
        Container remoteChest = container(4, 5, Blocks.CHEST);
        remoteChest.setItem(0, new ItemStack(Items.DIAMOND, 3));
        remoteChest.setItem(1, new ItemStack(Items.BREAD, 12));
        plate(4, 6, Kind.BUS, Medium.ITEM, "remote_chest");

        // Blocks set like this skip the placement logic, so give every cable its arms now.
        for (int x = 0; x < WIDTH; x++) {
            for (int z = 0; z < DEPTH; z++) {
                BlockState state = level.getBlockState(at(x, 0, z));
                if (state.getBlock() instanceof CableBlock) {
                    level.setBlock(at(x, 0, z), CableBlock.withConnections(level, at(x, 0, z), state), Block.UPDATE_ALL);
                }
            }
        }

        // Addresses, then the programs, which need to know them.
        CableNetwork.changed();
        CableNetwork.at(level, main.getBlockPos());
        CableNetwork.at(level, remote.getBlockPos());
        String mainAddress = mainAntenna.externalIp() + ":" + (RadioNetwork.PORT_BASE + main.hostNumber());
        String remoteAddress = remoteAntenna.externalIp() + ":" + (RadioNetwork.PORT_BASE + remote.hostNumber());
        ComputerManager manager = ComputerManager.get(level.getServer());
        ServerComputer mainComputer = manager.getOrStart(main);
        ServerComputer remoteComputer = manager.getOrStart(remote);
        folder(mainComputer, "/demos");
        for (String[] file : StageDemos.forMain(remoteAddress)) {
            write(mainComputer, "/demos/" + file[0], file[1]);
        }
        write(mainComputer, "/README.txt", StageDemos.readme(mainAddress, remoteAddress));
        folder(remoteComputer, "/demos");
        write(remoteComputer, "/demos/echo.py", StageDemos.ECHO);
        write(remoteComputer, "/README.txt", StageDemos.remoteReadme(mainAddress));
        // The second computer answers messages from the start, so the radio demo works at once.
        remoteComputer.submitLine("run /demos/echo.py");

        return "Stage built, " + WIDTH + " by " + DEPTH + " blocks, to the south-east of where you stood.\n"
                + "The near computer is 'remote'; the far one, with every device beside it, is 'main'.\n"
                + "Open main and type: cat README.txt   then, for example: run demos/1_items.py\n"
                + "main is " + mainAddress + ", remote is " + remoteAddress + ".";
    }
}
