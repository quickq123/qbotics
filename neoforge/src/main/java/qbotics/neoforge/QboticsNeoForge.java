package qbotics.neoforge;

import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import qbotics.test.QboticsGameTests;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.QboticsPlatform;
import qbotics.block.AntennaBlock;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CableBlock;
import qbotics.network.RadioNetwork;
import qbotics.block.ComputerBlock;
import qbotics.block.ComputerBlockEntity;
import qbotics.block.InterfaceBlock;
import qbotics.block.InterfaceBlockEntity;
import qbotics.block.PlateItem;
import qbotics.block.PortBlockEntity;
import qbotics.network.ItemPort;
import qbotics.network.NetworkDevice;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.VanillaContainerWrapper;
import qbotics.computer.ComputerManager;
import qbotics.net.IdePayloads;
import qbotics.net.TerminalInputPayload;
import qbotics.net.TerminalStatePayload;

/** NeoForge entry point. Keep this layer thin: real logic belongs in the common module. */
@Mod(Qbotics.MOD_ID)
public final class QboticsNeoForge {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Qbotics.MOD_ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Qbotics.MOD_ID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Qbotics.MOD_ID);

    private static final DeferredBlock<ComputerBlock> COMPUTER = BLOCKS.registerBlock("computer", ComputerBlock::new,
            properties -> properties.mapColor(MapColor.STONE).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> COMPUTER_ITEM = ITEMS.registerSimpleBlockItem(COMPUTER);
    private static final DeferredBlock<CableBlock> CABLE = BLOCKS.registerBlock("cable", NeoForgeCableBlock::new,
            properties -> properties.mapColor(MapColor.METAL).strength(0.5f).sound(SoundType.METAL).noOcclusion()
                    // Counted as solid so that flowing water and lava go around it rather than washing it away.
                    .forceSolidOn()
                    // Its shape depends on the plates its block entity keeps, not only its state.
                    .dynamicShape());
    private static final DeferredItem<BlockItem> CABLE_ITEM = ITEMS.registerSimpleBlockItem(CABLE);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("computer", () -> new BlockEntityType<>(ComputerBlockEntity::new, COMPUTER.get()));

    // The item blocks (design doc §11.0).
    private static final DeferredBlock<InterfaceBlock> INTERFACE = BLOCKS.registerBlock("interface", InterfaceBlock::new,
            properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(INTERFACE);
    // Files that travel (design doc §17.3, §17.6): hard drives, and the bay they go in.
    private static final DeferredBlock<qbotics.block.DriveBayBlock> DRIVE_BAY = BLOCKS.registerBlock("drive_bay", qbotics.block.DriveBayBlock::new,
            properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> DRIVE_BAY_ITEM = ITEMS.registerSimpleBlockItem(DRIVE_BAY);
    // The Uplink (design doc §9.4): its network's computers reach the real internet.
    private static final DeferredBlock<qbotics.uplink.UplinkBlock> UPLINK = BLOCKS.registerBlock("uplink", qbotics.uplink.UplinkBlock::new,
            properties -> properties.mapColor(MapColor.METAL).strength(3.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> UPLINK_ITEM = ITEMS.registerSimpleBlockItem(UPLINK);
    private static final DeferredBlock<qbotics.block.ProgrammerBlock> PROGRAMMER = BLOCKS.registerBlock("programmer", qbotics.block.ProgrammerBlock::new,
            properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> PROGRAMMER_ITEM = ITEMS.registerSimpleBlockItem(PROGRAMMER);
    // Displays (design doc §10): screens join side by side, so they glow and don't block light oddly.
    private static final DeferredBlock<qbotics.block.DisplayBlock> TEXT_MONITOR = BLOCKS.registerBlock("text_monitor", qbotics.block.DisplayBlock::monitor,
            properties -> properties.mapColor(MapColor.COLOR_BLACK).strength(1.5f).sound(SoundType.METAL).lightLevel(state -> 4));
    private static final DeferredItem<BlockItem> TEXT_MONITOR_ITEM = ITEMS.registerSimpleBlockItem(TEXT_MONITOR);
    private static final DeferredBlock<qbotics.block.DisplayBlock> CONTROL_PANEL = BLOCKS.registerBlock("control_panel", qbotics.block.DisplayBlock::panel,
            properties -> properties.mapColor(MapColor.COLOR_BLACK).strength(1.5f).sound(SoundType.METAL).lightLevel(state -> 4));
    private static final DeferredItem<BlockItem> CONTROL_PANEL_ITEM = ITEMS.registerSimpleBlockItem(CONTROL_PANEL);
    /** The Web Panel (design doc §10.3): pages of HTML and CSS, laid out in each player's game. */
    private static final DeferredBlock<qbotics.block.DisplayBlock> WEB_PANEL = BLOCKS.registerBlock("web_panel", qbotics.block.DisplayBlock::web,
            properties -> properties.mapColor(MapColor.COLOR_BLACK).strength(1.5f).sound(SoundType.METAL).lightLevel(state -> 4));
    private static final DeferredItem<BlockItem> WEB_PANEL_ITEM = ITEMS.registerSimpleBlockItem(WEB_PANEL);
    // Qbots (design doc §8): drawn by their renderer, so they can step, turn and swing.
    private static final DeferredBlock<qbotics.qbot.QbotBlock> IRON_QBOT = BLOCKS.registerBlock("iron_qbot", qbotics.qbot.QbotBlock::iron,
            properties -> properties.mapColor(MapColor.METAL).strength(2.5f).sound(SoundType.METAL).noOcclusion());
    /** A Qbot's item carries what it held, so (as a shulker box) one that carries things doesn't go in a shulker box or bundle. */
    private static final DeferredItem<BlockItem> IRON_QBOT_ITEM = ITEMS.registerItem("iron_qbot", properties -> new BlockItem(IRON_QBOT.get(), properties) {
        @Override
        public boolean canFitInsideContainerItems(net.minecraft.world.item.ItemStack stack) {
            return qbotics.qbot.QbotBlockEntity.mayHold(stack);
        }
    }, net.minecraft.world.item.Item.Properties::useBlockDescriptionPrefix);
    /** The Server (design doc §3.4): the late-game computer, 2 MB, with its own drives and uplink. */
    private static final DeferredBlock<qbotics.block.ServerBlock> SERVER = BLOCKS.registerBlock("server", qbotics.block.ServerBlock::new,
            properties -> properties.mapColor(MapColor.COLOR_BLACK).strength(3.0f).sound(SoundType.NETHERITE_BLOCK));
    private static final DeferredItem<BlockItem> SERVER_ITEM = ITEMS.registerSimpleBlockItem(SERVER);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.ServerBlockEntity>> SERVER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("server", () -> new BlockEntityType<>(qbotics.block.ServerBlockEntity::new, SERVER.get()));
    /** RAM: the part that gives a Server its memory. */
    private static final DeferredItem<net.minecraft.world.item.Item> RAM_ITEM = ITEMS.registerSimpleItem("ram");
    /** A laptop (design doc §5.6): a player's own carried computer. */
    private static final DeferredItem<qbotics.laptop.LaptopItem> LAPTOP_ITEM = ITEMS.registerItem("laptop", qbotics.laptop.LaptopItem::new);
    /** A tablet (design doc §10.6): a screen in the hand. */
    static final DeferredItem<qbotics.tablet.TabletItem> TABLET_ITEM = ITEMS.registerItem("tablet", qbotics.tablet.TabletItem::new);
    private static final DeferredBlock<qbotics.qbot.QbotPartBlock> QBOT_PART = BLOCKS.registerBlock("qbot_part", qbotics.qbot.QbotPartBlock::new,
            properties -> properties.noOcclusion().noLootTable());
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.qbot.QbotBlockEntity>> QBOT_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("qbot", () -> new BlockEntityType<>(qbotics.qbot.QbotBlockEntity::new, IRON_QBOT.get()));
    // Upgrades (fitted to antennas; later to Qbots).
    private static final DeferredItem<qbotics.upgrade.UpgradeItem> TRANSPORT_UPGRADE = ITEMS.registerItem("transport_upgrade",
            properties -> new qbotics.upgrade.UpgradeItem(qbotics.upgrade.UpgradeItem.Kind.TRANSPORT, properties.stacksTo(1)));
    private static final DeferredItem<qbotics.upgrade.UpgradeItem> CHUNK_LOADER_UPGRADE = ITEMS.registerItem("chunk_loader_upgrade",
            properties -> new qbotics.upgrade.UpgradeItem(qbotics.upgrade.UpgradeItem.Kind.CHUNK_LOADER, properties.stacksTo(1)));
    private static final DeferredItem<qbotics.upgrade.UpgradeItem> SENSE_UPGRADE = ITEMS.registerItem("sense_upgrade",
            properties -> new qbotics.upgrade.UpgradeItem(qbotics.upgrade.UpgradeItem.Kind.SENSE, properties.stacksTo(1)));
    private static final DeferredItem<qbotics.upgrade.UpgradeItem> NETHERITE_UPGRADE = ITEMS.registerItem("netherite_upgrade",
            properties -> new qbotics.upgrade.UpgradeItem(qbotics.upgrade.UpgradeItem.Kind.NETHERITE, properties.stacksTo(1).fireResistant()));
    /**
     * The tickets Chunk Loader Upgrades hold chunks with. When a world loads, they are handed
     * to ChunkLoaders, which lets go of any whose antenna doesn't turn up within a few seconds
     * (blocks aren't looked at here: the world is still loading).
     */
    static final net.neoforged.neoforge.common.world.chunk.TicketController CHUNK_TICKETS =
            new net.neoforged.neoforge.common.world.chunk.TicketController(Qbotics.id("chunk_loader"), (level, tickets) -> {
                for (var entry : tickets.getBlockTickets().entrySet()) {
                    qbotics.upgrade.ChunkLoaders.seed(level, entry.getKey(), entry.getValue().normal());
                }
            });
    private static final DeferredItem<qbotics.block.HardDriveItem> HARD_DRIVE_ITEM = ITEMS.registerItem("hard_drive", qbotics.block.HardDriveItem::new);
    // Plates that go on cables: no blocks of their own.
    // The Serial Buses: one per medium, and the Universal Serial Bus (USB), which carries all three.
    private static final DeferredItem<PlateItem> BUS_ITEM = ITEMS.registerItem("bus",
            properties -> new PlateItem(NetworkDevice.Kind.BUS, qbotics.network.Medium.ITEM, properties));
    // World plates (design doc §11.0.1): each handles what it needs itself, so there are no fluid or energy versions.
    private static final DeferredItem<PlateItem> PIN_ITEM = ITEMS.registerItem("pin",
            properties -> new PlateItem(NetworkDevice.Kind.PIN, qbotics.network.Medium.ITEM, properties));
    private static final DeferredItem<PlateItem> PLACER_ITEM = ITEMS.registerItem("placer",
            properties -> new PlateItem(NetworkDevice.Kind.PLACER, qbotics.network.Medium.ITEM, properties));
    private static final DeferredItem<PlateItem> BREAKER_ITEM = ITEMS.registerItem("breaker",
            properties -> new PlateItem(NetworkDevice.Kind.BREAKER, qbotics.network.Medium.ITEM, properties));
    private static final DeferredItem<PlateItem> DROPPER_ITEM = ITEMS.registerItem("dropper",
            properties -> new PlateItem(NetworkDevice.Kind.DROPPER, qbotics.network.Medium.ITEM, properties));
    private static final DeferredItem<PlateItem> FLUID_BUS_ITEM = ITEMS.registerItem("fluid_bus",
            properties -> new PlateItem(NetworkDevice.Kind.BUS, qbotics.network.Medium.FLUID, properties));
    private static final DeferredItem<PlateItem> ENERGY_BUS_ITEM = ITEMS.registerItem("energy_bus",
            properties -> new PlateItem(NetworkDevice.Kind.BUS, qbotics.network.Medium.ENERGY, properties));
    private static final DeferredItem<PlateItem> USB_ITEM = ITEMS.registerItem("usb",
            properties -> new PlateItem(NetworkDevice.Kind.BUS, qbotics.network.Medium.UNIVERSAL, properties));

    // The stores for fluid and energy: full blocks, like the Interface.
    private static final DeferredBlock<qbotics.block.StoreBlock> FLUID_INTERFACE = BLOCKS.registerBlock("fluid_interface",
            qbotics.block.StoreBlock::fluid, properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredBlock<qbotics.block.StoreBlock> ENERGY_INTERFACE = BLOCKS.registerBlock("energy_interface",
            qbotics.block.StoreBlock::energy, properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredBlock<qbotics.block.StoreBlock> EXPERIENCE_INTERFACE = BLOCKS.registerBlock("experience_interface",
            qbotics.block.StoreBlock::experience, properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> EXPERIENCE_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(EXPERIENCE_INTERFACE);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.ExperienceInterfaceBlockEntity>> EXPERIENCE_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("experience_interface",
                    () -> new BlockEntityType<>(qbotics.block.ExperienceInterfaceBlockEntity::new, EXPERIENCE_INTERFACE.get()));
    private static final DeferredItem<BlockItem> FLUID_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(FLUID_INTERFACE);
    // The game interfaces. The Command Interface is a command block's power, so it is made like
    // one: no recipe, no drop, unbreakable in survival, and placed only by an operator in creative.
    private static final DeferredBlock<qbotics.block.GameInterfaceBlock> PLAYER_INTERFACE = BLOCKS.registerBlock("player_interface",
            qbotics.block.GameInterfaceBlock::player, properties -> properties.mapColor(MapColor.COLOR_PURPLE).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredBlock<qbotics.block.GameInterfaceBlock> CHAT_INTERFACE = BLOCKS.registerBlock("chat_interface",
            qbotics.block.GameInterfaceBlock::chat, properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredBlock<qbotics.block.GameInterfaceBlock> COMMAND_INTERFACE = BLOCKS.registerBlock("command_interface",
            qbotics.block.GameInterfaceBlock::command, properties -> properties.mapColor(MapColor.COLOR_ORANGE)
                    .requiresCorrectToolForDrops().strength(-1.0f, 3600000.0f).noLootTable().sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> PLAYER_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(PLAYER_INTERFACE);
    // The Entity Sensor: sees and acts on the entities in a box around it.
    private static final DeferredBlock<qbotics.block.EntitySensorBlock> ENTITY_SENSOR = BLOCKS.registerBlock("entity_sensor",
            qbotics.block.EntitySensorBlock::new, properties -> properties.mapColor(MapColor.COLOR_CYAN).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> ENTITY_SENSOR_ITEM = ITEMS.registerSimpleBlockItem(ENTITY_SENSOR);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.EntitySensorBlockEntity>> ENTITY_SENSOR_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("entity_sensor", () -> new BlockEntityType<>(qbotics.block.EntitySensorBlockEntity::new, ENTITY_SENSOR.get()));
    // The Collector: a chest's worth of slots that picks up what lies in a box around it.
    private static final DeferredBlock<qbotics.block.CollectorBlock> COLLECTOR = BLOCKS.registerBlock("collector",
            qbotics.block.CollectorBlock::new, properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> COLLECTOR_ITEM = ITEMS.registerSimpleBlockItem(COLLECTOR);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.CollectorBlockEntity>> COLLECTOR_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("collector", () -> new BlockEntityType<>(qbotics.block.CollectorBlockEntity::new, COLLECTOR.get()));
    // The Block Sensor: reads, watches and works the blocks in a small box near it.
    private static final DeferredBlock<qbotics.block.BlockSensorBlock> BLOCK_SENSOR = BLOCKS.registerBlock("block_sensor",
            qbotics.block.BlockSensorBlock::new, properties -> properties.mapColor(MapColor.COLOR_GREEN).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> BLOCK_SENSOR_ITEM = ITEMS.registerSimpleBlockItem(BLOCK_SENSOR);
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.BlockSensorBlockEntity>> BLOCK_SENSOR_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("block_sensor", () -> new BlockEntityType<>(qbotics.block.BlockSensorBlockEntity::new, BLOCK_SENSOR.get()));
    private static final DeferredItem<BlockItem> CHAT_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(CHAT_INTERFACE);
    private static final DeferredItem<net.minecraft.world.item.GameMasterBlockItem> COMMAND_INTERFACE_ITEM = ITEMS.registerItem("command_interface",
            properties -> new net.minecraft.world.item.GameMasterBlockItem(COMMAND_INTERFACE.get(), properties),
            () -> new net.minecraft.world.item.Item.Properties().useBlockDescriptionPrefix().rarity(net.minecraft.world.item.Rarity.EPIC));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.GameInterfaceBlockEntity>> GAME_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("game_interface", () -> new BlockEntityType<>(qbotics.block.GameInterfaceBlockEntity::new,
                    PLAYER_INTERFACE.get(), CHAT_INTERFACE.get(), COMMAND_INTERFACE.get()));
    private static final DeferredItem<BlockItem> ENERGY_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(ENERGY_INTERFACE);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.FluidInterfaceBlockEntity>> FLUID_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("fluid_interface",
                    () -> new BlockEntityType<>(qbotics.block.FluidInterfaceBlockEntity::new, FLUID_INTERFACE.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.EnergyInterfaceBlockEntity>> ENERGY_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("energy_interface",
                    () -> new BlockEntityType<>(qbotics.block.EnergyInterfaceBlockEntity::new, ENERGY_INTERFACE.get()));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.DisplayBlockEntity>> DISPLAY_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("display", () -> new BlockEntityType<>(qbotics.block.DisplayBlockEntity::new, TEXT_MONITOR.get(), CONTROL_PANEL.get(), WEB_PANEL.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.ProgrammerBlockEntity>> PROGRAMMER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("programmer", () -> new BlockEntityType<>(qbotics.block.ProgrammerBlockEntity::new, PROGRAMMER.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.uplink.UplinkBlockEntity>> UPLINK_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("uplink", () -> new BlockEntityType<>(qbotics.uplink.UplinkBlockEntity::new, UPLINK.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.DriveBayBlockEntity>> DRIVE_BAY_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("drive_bay", () -> new BlockEntityType<>(qbotics.block.DriveBayBlockEntity::new, DRIVE_BAY.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InterfaceBlockEntity>> INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("interface", () -> new BlockEntityType<>(InterfaceBlockEntity::new, INTERFACE.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PortBlockEntity>> PORT_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("port", () -> new BlockEntityType<>(PortBlockEntity::new, CABLE.get()));

    // Parts: made in the crafting grid, used up in other recipes.
    // Screens, in two tiers: LCD (early game) and LED (mid game), and the parts each is made from.
    private static final DeferredItem<net.minecraft.world.item.Item> LIQUID_CRYSTAL_PANEL = ITEMS.registerSimpleItem("liquid_crystal_panel");
    private static final DeferredItem<net.minecraft.world.item.Item> LCD_SCREEN = ITEMS.registerSimpleItem("lcd_screen");
    private static final DeferredItem<net.minecraft.world.item.Item> LIGHT_EMITTING_DIODE = ITEMS.registerSimpleItem("light_emitting_diode");
    private static final DeferredItem<net.minecraft.world.item.Item> LED_SCREEN = ITEMS.registerSimpleItem("led_screen");
    private static final DeferredItem<net.minecraft.world.item.Item> INTEGRATED_CIRCUIT_ITEM = ITEMS.registerSimpleItem("integrated_circuit");
    private static final DeferredItem<qbotics.block.ProbeItem> PROBE_ITEM = ITEMS.registerItem("probe",
            properties -> new qbotics.block.ProbeItem(properties.stacksTo(1)));

    private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Qbotics.MOD_ID);
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.InterfaceMenu>> INTERFACE_MENU =
            MENUS.register("interface", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.InterfaceMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.laptop.LaptopMenu>> LAPTOP_MENU =
            MENUS.register("laptop", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.laptop.LaptopMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.qbot.QbotMenu>> QBOT_MENU =
            MENUS.register("qbot", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.qbot.QbotMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.AntennaMenu>> ANTENNA_MENU =
            MENUS.register("antenna", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.AntennaMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.ServerMenu>> SERVER_MENU =
            MENUS.register("server", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.ServerMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.ProgrammerMenu>> PROGRAMMER_MENU =
            MENUS.register("programmer", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.ProgrammerMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.DriveBayMenu>> DRIVE_BAY_MENU =
            MENUS.register("drive_bay", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.DriveBayMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.FluidInterfaceMenu>> FLUID_INTERFACE_MENU =
            MENUS.register("fluid_interface", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.FluidInterfaceMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.EnergyInterfaceMenu>> ENERGY_INTERFACE_MENU =
            MENUS.register("energy_interface", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.EnergyInterfaceMenu(containerId, inventory)));

    // Antennas, one block per range tier (design doc §9.1).
    private static final DeferredBlock<AntennaBlock> ANTENNA_SHORT = antenna("antenna_short", RadioNetwork.Tier.SHORT);
    private static final DeferredBlock<AntennaBlock> ANTENNA_LONG = antenna("antenna_long", RadioNetwork.Tier.LONG);
    private static final DeferredBlock<AntennaBlock> ANTENNA_INFINITE = antenna("antenna_infinite", RadioNetwork.Tier.INFINITE);
    private static final DeferredItem<BlockItem> ANTENNA_SHORT_ITEM = ITEMS.registerSimpleBlockItem(ANTENNA_SHORT);
    private static final DeferredItem<BlockItem> ANTENNA_LONG_ITEM = ITEMS.registerSimpleBlockItem(ANTENNA_LONG);
    private static final DeferredItem<BlockItem> ANTENNA_INFINITE_ITEM = ITEMS.registerSimpleBlockItem(ANTENNA_INFINITE);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<AntennaBlockEntity>> ANTENNA_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("antenna", () -> new BlockEntityType<>(AntennaBlockEntity::new,
                    ANTENNA_SHORT.get(), ANTENNA_LONG.get(), ANTENNA_INFINITE.get()));

    private static DeferredBlock<AntennaBlock> antenna(String name, RadioNetwork.Tier tier) {
        return BLOCKS.registerBlock(name, properties -> new AntennaBlock(tier, properties),
                properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL).noOcclusion());
    }

    /** The mod's own tab in the creative inventory, holding everything it adds. */
    private static final DeferredRegister<net.minecraft.advancements.CriterionTrigger<?>> TRIGGERS =
            DeferredRegister.create(Registries.TRIGGER_TYPE, Qbotics.MOD_ID);
    private static final DeferredHolder<net.minecraft.advancements.CriterionTrigger<?>, qbotics.lesson.LessonTrigger> LESSON_TRIGGER =
            TRIGGERS.register("lesson", qbotics.lesson.LessonTrigger::new);

    private static final DeferredRegister<CreativeModeTab> CREATIVE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, Qbotics.MOD_ID);
    private static final DeferredHolder<CreativeModeTab, CreativeModeTab> CREATIVE_TAB = CREATIVE_TABS.register("qbotics",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.qbotics"))
                    .icon(() -> new ItemStack(COMPUTER_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(COMPUTER_ITEM.get());
                        output.accept(CABLE_ITEM.get());
                        output.accept(INTERFACE_ITEM.get());
                        output.accept(FLUID_INTERFACE_ITEM.get());
                        output.accept(ENERGY_INTERFACE_ITEM.get());
                        output.accept(EXPERIENCE_INTERFACE_ITEM.get());
                        output.accept(PLAYER_INTERFACE_ITEM.get());
                        output.accept(CHAT_INTERFACE_ITEM.get());
                        output.accept(ENTITY_SENSOR_ITEM.get());
                        output.accept(COLLECTOR_ITEM.get());
                        output.accept(BLOCK_SENSOR_ITEM.get());
                        output.accept(BUS_ITEM.get());
                        output.accept(FLUID_BUS_ITEM.get());
                        output.accept(ENERGY_BUS_ITEM.get());
                        output.accept(USB_ITEM.get());
                        output.accept(PIN_ITEM.get());
                        output.accept(PLACER_ITEM.get());
                        output.accept(BREAKER_ITEM.get());
                        output.accept(DROPPER_ITEM.get());
                        output.accept(ANTENNA_SHORT_ITEM.get());
                        output.accept(ANTENNA_LONG_ITEM.get());
                        output.accept(ANTENNA_INFINITE_ITEM.get());
                        output.accept(PROBE_ITEM.get());
                        output.accept(DRIVE_BAY_ITEM.get());
                        output.accept(UPLINK_ITEM.get());
                        output.accept(HARD_DRIVE_ITEM.get());
                        output.accept(PROGRAMMER_ITEM.get());
                        output.accept(TEXT_MONITOR_ITEM.get());
                        output.accept(CONTROL_PANEL_ITEM.get());
                        output.accept(WEB_PANEL_ITEM.get());
                        output.accept(IRON_QBOT_ITEM.get());
                        output.accept(SERVER_ITEM.get());
                        output.accept(LAPTOP_ITEM.get());
                        output.accept(TABLET_ITEM.get());
                        output.accept(TRANSPORT_UPGRADE.get());
                        output.accept(CHUNK_LOADER_UPGRADE.get());
                        output.accept(SENSE_UPGRADE.get());
                        output.accept(NETHERITE_UPGRADE.get());
                        output.accept(LIQUID_CRYSTAL_PANEL.get());
                        output.accept(LCD_SCREEN.get());
                        output.accept(LIGHT_EMITTING_DIODE.get());
                        output.accept(LED_SCREEN.get());
                        output.accept(INTEGRATED_CIRCUIT_ITEM.get());
                        output.accept(RAM_ITEM.get());
                    })
                    .build());

    private static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Qbotics.MOD_ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> DRIVE_ID =
            DATA_COMPONENTS.registerComponentType("drive_id",
                    builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    /** On a hard drive: the ID its files are kept under. */
    /** On a tablet: the ID its screens are kept under. */
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> TABLET_ID =
            DATA_COMPONENTS.registerComponentType("tablet_id",
                    builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> MEDIA_ID =
            DATA_COMPONENTS.registerComponentType("media_id",
                    builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    static CableBlock cableBlock() {
        return CABLE.get();
    }

    private static final DeferredHolder<DataComponentType<?>, DataComponentType<Long>> XP_POINTS =
            DATA_COMPONENTS.registerComponentType("xp_points", builder -> builder
                    .persistent(com.mojang.serialization.Codec.LONG).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_LONG));
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> PROBE_MODE =
            DATA_COMPONENTS.registerComponentType("probe_mode", builder -> builder
                    .persistent(com.mojang.serialization.Codec.INT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    public QboticsNeoForge(IEventBus modBus, ModContainer container) {
        // The server settings (limits, boot queue, scheduler): config/qbotics-server.toml.
        // COMMON, not SERVER: NeoForge copies every SERVER settings file, whole, to each player who joins,
        // and this one holds the server's AI key and addresses. The file's name and place are unchanged.
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.COMMON, QboticsServerConfig.SPEC, "qbotics-server.toml");
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Loading event) -> QboticsServerConfig.apply(event));
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) -> QboticsServerConfig.apply(event));
        QboticsContent.COMPUTER_BLOCK = COMPUTER;
        QboticsContent.COMPUTER_BLOCK_ENTITY = COMPUTER_BLOCK_ENTITY;
        QboticsContent.CABLE_BLOCK = CABLE;
        QboticsContent.DRIVE_ID = DRIVE_ID;
        QboticsContent.MEDIA_ID = MEDIA_ID;
        QboticsContent.TABLET_ID = TABLET_ID;
        QboticsContent.TABLET_ITEM = TABLET_ITEM;
        QboticsContent.DRIVE_BAY_BLOCK_ENTITY = DRIVE_BAY_BLOCK_ENTITY;
        QboticsContent.UPLINK_BLOCK_ENTITY = UPLINK_BLOCK_ENTITY;
        QboticsContent.UPLINK_BLOCK = UPLINK;
        QboticsContent.PROGRAMMER_BLOCK_ENTITY = PROGRAMMER_BLOCK_ENTITY;
        QboticsContent.DISPLAY_BLOCK_ENTITY = DISPLAY_BLOCK_ENTITY;
        QboticsContent.TEXT_MONITOR_BLOCK = TEXT_MONITOR;
        QboticsContent.CONTROL_PANEL_BLOCK = CONTROL_PANEL;
        QboticsContent.WEB_PANEL_BLOCK = WEB_PANEL;
        QboticsContent.PROGRAMMER_MENU = PROGRAMMER_MENU;
        QboticsContent.ANTENNA_MENU = ANTENNA_MENU;
        QboticsContent.SERVER_MENU = SERVER_MENU;
        QboticsContent.IRON_QBOT_BLOCK = IRON_QBOT;
        QboticsContent.SERVER_BLOCK = SERVER;
        QboticsContent.SERVER_BLOCK_ENTITY = SERVER_BLOCK_ENTITY;
        QboticsContent.QBOT_BLOCK_ENTITY = QBOT_BLOCK_ENTITY;
        QboticsContent.QBOT_MENU = QBOT_MENU;
        QboticsContent.LAPTOP_MENU = LAPTOP_MENU;
        QboticsContent.LAPTOP_ITEM = LAPTOP_ITEM;
        QboticsContent.QBOT_PART_BLOCK = QBOT_PART;
        QboticsContent.DRIVE_BAY_MENU = DRIVE_BAY_MENU;
        QboticsContent.PROBE_MODE = PROBE_MODE;
        QboticsContent.INTERFACE_BLOCK = INTERFACE;
        QboticsContent.PROBE_ITEM = PROBE_ITEM;
        QboticsContent.PLATE_ITEMS = java.util.Map.ofEntries(
                java.util.Map.entry("pin", PIN_ITEM),
                java.util.Map.entry("placer", PLACER_ITEM),
                java.util.Map.entry("breaker", BREAKER_ITEM),
                java.util.Map.entry("dropper", DROPPER_ITEM),
                java.util.Map.entry("bus", BUS_ITEM),
                java.util.Map.entry("fluid_bus", FLUID_BUS_ITEM),
                java.util.Map.entry("energy_bus", ENERGY_BUS_ITEM),
                java.util.Map.entry("universal_bus", USB_ITEM));
        QboticsContent.FLUID_INTERFACE_BLOCK = FLUID_INTERFACE;
        QboticsContent.ENERGY_INTERFACE_BLOCK = ENERGY_INTERFACE;
        QboticsContent.EXPERIENCE_INTERFACE_BLOCK = EXPERIENCE_INTERFACE;
        QboticsContent.PLAYER_INTERFACE_BLOCK = PLAYER_INTERFACE;
        QboticsContent.ENTITY_SENSOR_BLOCK = ENTITY_SENSOR;
        QboticsContent.ENTITY_SENSOR_BLOCK_ENTITY = ENTITY_SENSOR_BLOCK_ENTITY;
        QboticsContent.COLLECTOR_BLOCK = COLLECTOR;
        QboticsContent.COLLECTOR_BLOCK_ENTITY = COLLECTOR_BLOCK_ENTITY;
        QboticsContent.BLOCK_SENSOR_BLOCK = BLOCK_SENSOR;
        QboticsContent.BLOCK_SENSOR_BLOCK_ENTITY = BLOCK_SENSOR_BLOCK_ENTITY;
        QboticsContent.CHAT_INTERFACE_BLOCK = CHAT_INTERFACE;
        QboticsContent.COMMAND_INTERFACE_BLOCK = COMMAND_INTERFACE;
        QboticsContent.GAME_INTERFACE_BLOCK_ENTITY = GAME_INTERFACE_BLOCK_ENTITY;
        QboticsContent.EXPERIENCE_INTERFACE_BLOCK_ENTITY = EXPERIENCE_INTERFACE_BLOCK_ENTITY;
        QboticsContent.XP_POINTS = XP_POINTS;
        QboticsContent.LESSON_TRIGGER = LESSON_TRIGGER;
        QboticsContent.FLUID_INTERFACE_BLOCK_ENTITY = FLUID_INTERFACE_BLOCK_ENTITY;
        QboticsContent.ENERGY_INTERFACE_BLOCK_ENTITY = ENERGY_INTERFACE_BLOCK_ENTITY;
        QboticsContent.INTERFACE_BLOCK_ENTITY = INTERFACE_BLOCK_ENTITY;
        QboticsContent.INTERFACE_MENU = INTERFACE_MENU;
        QboticsContent.FLUID_INTERFACE_MENU = FLUID_INTERFACE_MENU;
        QboticsContent.ENERGY_INTERFACE_MENU = ENERGY_INTERFACE_MENU;
        QboticsContent.PORT_BLOCK_ENTITY = PORT_BLOCK_ENTITY;
        QboticsContent.ANTENNA_SHORT_BLOCK = ANTENNA_SHORT;
        QboticsContent.ANTENNA_LONG_BLOCK = ANTENNA_LONG;
        QboticsContent.ANTENNA_INFINITE_BLOCK = ANTENNA_INFINITE;
        QboticsContent.ANTENNA_BLOCK_ENTITY = ANTENNA_BLOCK_ENTITY;

        DATA_COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(QboticsNeoForge::registerPayloads);
        CREATIVE_TABS.register(modBus);
        MENUS.register(modBus);
        TRIGGERS.register(modBus);
        // Hoppers and other mods' pipes see the Input block as an ordinary (filtered) inventory.
        modBus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.Item.BLOCK, INTERFACE_BLOCK_ENTITY.get(), (input, side) -> VanillaContainerWrapper.of(input));
            // Hoppers and pipes take from a Collector (and put into it) as from a chest.
            event.registerBlockEntity(Capabilities.Item.BLOCK, COLLECTOR_BLOCK_ENTITY.get(), (collector, side) -> VanillaContainerWrapper.of(collector));
            // The fluid and energy stores are ordinary tanks and batteries to other mods' pipes and cables.
            event.registerBlockEntity(Capabilities.Fluid.BLOCK, FLUID_INTERFACE_BLOCK_ENTITY.get(), (store, side) -> MediaHandlers.of(store));
            event.registerBlockEntity(Capabilities.Energy.BLOCK, ENERGY_INTERFACE_BLOCK_ENTITY.get(), (store, side) -> MediaHandlers.of(store));
            // A Qbot parked by a hopper, pipe or cable: its 16 slots, two tanks and energy cell are theirs to
            // fill and empty, from any side, as a chest's, a tank's and a battery's are.
            event.registerBlockEntity(Capabilities.Item.BLOCK, QBOT_BLOCK_ENTITY.get(), (qbot, side) -> VanillaContainerWrapper.of(qbot.items()));
            event.registerBlockEntity(Capabilities.Fluid.BLOCK, QBOT_BLOCK_ENTITY.get(), (qbot, side) -> MediaHandlers.tanksOf(qbot));
            event.registerBlockEntity(Capabilities.Energy.BLOCK, QBOT_BLOCK_ENTITY.get(), (qbot, side) -> MediaHandlers.cellOf(qbot));
            // A bus that is one end of a tunnel offers, on its face, the container behind the other end.
            event.registerBlockEntity(Capabilities.Item.BLOCK, PORT_BLOCK_ENTITY.get(), TunnelHandlers::items);
            event.registerBlockEntity(Capabilities.Fluid.BLOCK, PORT_BLOCK_ENTITY.get(), TunnelHandlers::fluid);
            event.registerBlockEntity(Capabilities.Energy.BLOCK, PORT_BLOCK_ENTITY.get(), TunnelHandlers::energy);
        });
        if (Boolean.getBoolean("qbotics.gameTests")) {
            registerGameTests(modBus);
        }

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            ComputerManager.get(event.getServer()).tick(event.getServer());
            qbotics.upgrade.ChunkLoaders.tick(event.getServer());
            qbotics.network.Tunnels.tick(event.getServer());
        });
        // Chunk Loader Upgrades hold chunks through these tickets, saved with the world.
        modBus.addListener((net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent event) -> event.register(CHUNK_TICKETS));
        // /qbotics stage: builds the test stage beside the player (see TestStage). For players who may use commands.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.RegisterCommandsEvent event) -> event.getDispatcher().register(
                net.minecraft.commands.Commands.literal("qbotics")
                        // /qbotics limits: a player's active devices against their limits; "all" (operators) lists everyone's.
                        .then(net.minecraft.commands.Commands.literal("limits")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    for (String line : qbotics.limits.Limits.report(context.getSource().getServer(), player.getUUID())) {
                                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                                    }
                                    return 1;
                                })
                                .then(net.minecraft.commands.Commands.literal("all")
                                        .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                                        .executes(context -> {
                                            for (String line : qbotics.limits.Limits.report(context.getSource().getServer(), null)) {
                                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                                            }
                                            return 1;
                                        })))
                        // /qbotics ai: the AI model this server offers; "test" (operators) tries it again.
                        .then(net.minecraft.commands.Commands.literal("ai")
                                .executes(context -> {
                                    boolean operator = net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS)
                                            .test(context.getSource());
                                    for (String line : qbotics.agent.ServerModel.report(operator)) {
                                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                                    }
                                    return 1;
                                })
                                .then(net.minecraft.commands.Commands.literal("test")
                                        .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                                        .executes(context -> {
                                            net.minecraft.commands.CommandSourceStack source = context.getSource();
                                            net.minecraft.server.MinecraftServer server = source.getServer();
                                            source.sendSuccess(() -> Component.literal("Testing this server's AI model..."), false);
                                            qbotics.agent.ServerModel.test(() -> server.execute(() -> {
                                                qbotics.agent.ServerModel.tellEveryone(server);
                                                for (String line : qbotics.agent.ServerModel.report(true)) {
                                                    source.sendSuccess(() -> Component.literal(line), false);
                                                }
                                            }));
                                            return 1;
                                        })))
                        // /qbotics git token: the screen where a player gives their GitHub token, for git push.
                        .then(net.minecraft.commands.Commands.literal("git")
                                .then(net.minecraft.commands.Commands.literal("token")
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            Qbotics.platform().sendToPlayer(player, new qbotics.net.GitPayloads.OpenToken(
                                                    qbotics.uplink.GitTokens.has(context.getSource().getServer(), player.getUUID()),
                                                    qbotics.QboticsConfig.gitAllowPush));
                                            return 1;
                                        })))
                        // /qbotics ssh: the keys a player logs in to the SSH gateway with, and whether it is on.
                        .then(net.minecraft.commands.Commands.literal("ssh")
                                .executes(context -> {
                                    for (String line : qbotics.gateway.SshCommands.status(context.getSource().getServer(), context.getSource().getPlayerOrException())) {
                                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                                    }
                                    return 1;
                                })
                                .then(net.minecraft.commands.Commands.literal("key")
                                        .then(net.minecraft.commands.Commands.literal("add")
                                                .then(net.minecraft.commands.Commands.argument("key", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                                        .executes(context -> {
                                                            String line = qbotics.gateway.SshCommands.add(context.getSource().getServer(), context.getSource().getPlayerOrException(),
                                                                    com.mojang.brigadier.arguments.StringArgumentType.getString(context, "key"));
                                                            context.getSource().sendSuccess(() -> Component.literal(line), false);
                                                            return 1;
                                                        })))
                                        .then(net.minecraft.commands.Commands.literal("list")
                                                .executes(context -> {
                                                    for (String line : qbotics.gateway.SshCommands.list(context.getSource().getServer(), context.getSource().getPlayerOrException())) {
                                                        context.getSource().sendSuccess(() -> Component.literal(line), false);
                                                    }
                                                    return 1;
                                                }))
                                        .then(net.minecraft.commands.Commands.literal("remove")
                                                .then(net.minecraft.commands.Commands.argument("number", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                                        .executes(context -> {
                                                            String line = qbotics.gateway.SshCommands.remove(context.getSource().getServer(), context.getSource().getPlayerOrException(),
                                                                    com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "number"));
                                                            context.getSource().sendSuccess(() -> Component.literal(line), false);
                                                            return 1;
                                                        })))))
                        .then(net.minecraft.commands.Commands.literal("stage")
                                .requires(net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            // The stage goes to the south-east, leaving the player standing just outside its corner.
                            String built = qbotics.stage.TestStage.build(player.level(), player.blockPosition().offset(2, 0, 2), player.getUUID());
                            for (String line : built.split("\n")) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            if (!player.getInventory().contains(stack -> stack.getItem() instanceof qbotics.block.ProbeItem)) {
                                player.getInventory().add(new ItemStack(PROBE_ITEM.get()));
                            }
                            return 1;
                        }))));
        // The server, for what needs its registries outside a call (the game's item syntax in filters).
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerAboutToStartEvent event) -> qbotics.Qbotics.setServer(event.getServer()));
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStoppedEvent event) -> qbotics.Qbotics.setServer(null));
        // The SSH gateway (design doc §9.5) starts with the server if the config turns it on.
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.server.ServerStartedEvent event) -> {
            qbotics.gateway.SshGateway.start(event.getServer());
            qbotics.agent.GameKnowledge.warm(event.getServer()); // the recipe index, ahead of the first question
            // The AI model this server offers, if one is set up: tested before it is offered.
            net.minecraft.server.MinecraftServer started = event.getServer();
            qbotics.agent.ServerModel.test(() -> started.execute(() -> qbotics.agent.ServerModel.tellEveryone(started)));
        });
        // A chunk loading or unloading can lengthen or shorten a network that runs to its edge (plain cables
        // have no block entity to say so themselves).
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Load event) -> {
            if (event.getLevel() instanceof ServerLevel) {
                qbotics.network.CableNetwork.chunkChanged(event.getChunk().getPos().x(), event.getChunk().getPos().z());
            }
        });
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.level.ChunkEvent.Unload event) -> {
            if (event.getLevel() instanceof ServerLevel) {
                qbotics.network.CableNetwork.chunkChanged(event.getChunk().getPos().x(), event.getChunk().getPos().z());
            }
        });
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            qbotics.gateway.SshGateway.stop();
            ComputerManager.shutDown(event.getServer());
            qbotics.upgrade.ChunkLoaders.clear();
            qbotics.network.Tunnels.clear();
            // After the computers have stopped: what else held on to this server's world.
            qbotics.network.Watches.clear();
            qbotics.network.Boundaries.clear();
            qbotics.display.Displays.clear();
        });
        // What players say in chat reaches the programs listening to a Chat Interface (on the next tick, on the server thread).
        NeoForge.EVENT_BUS.addListener((net.neoforged.neoforge.event.ServerChatEvent event) -> {
            ServerPlayer player = event.getPlayer();
            String text = event.getRawText();
            player.level().getServer().execute(() -> qbotics.network.GameInterfaceCalls.heard(player.level().getServer(), player, text));
        });
        // The Command Interface sits with the command block, for operators only.
        modBus.addListener((net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == net.minecraft.world.item.CreativeModeTabs.OP_BLOCKS && event.hasPermissions()) {
                event.accept(COMMAND_INTERFACE_ITEM.get());
            }
        });
        // A laptop crafted from a computer carries that computer's drive, to copy its files in when first opened.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.ItemCraftedEvent event) -> {
            if (!(event.getCrafting().getItem() instanceof qbotics.laptop.LaptopItem)) {
                return;
            }
            for (int slot = 0; slot < event.getInventory().getContainerSize(); slot++) {
                java.util.UUID drive = event.getInventory().getItem(slot).get(QboticsContent.DRIVE_ID.get());
                if (drive != null) {
                    event.getCrafting().set(QboticsContent.DRIVE_ID.get(), drive);
                }
            }
        });
        // A Qbot is built from a laptop: the hard drives in that laptop go back to the player, not into the Qbot.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.ItemCraftedEvent event) -> {
            if (!(event.getCrafting().getItem() instanceof net.minecraft.world.item.BlockItem block
                    && block.getBlock() instanceof qbotics.qbot.QbotBlock) || event.getEntity().level().isClientSide()) {
                return;
            }
            for (int slot = 0; slot < event.getInventory().getContainerSize(); slot++) {
                net.minecraft.world.item.ItemStack laptop = event.getInventory().getItem(slot);
                if (laptop.getItem() instanceof qbotics.laptop.LaptopItem) {
                    qbotics.laptop.Laptops.disks(laptop).nonEmptyItemCopyStream()
                            .forEach(disk -> event.getEntity().getInventory().placeItemBackInInventory(disk));
                    laptop.remove(net.minecraft.core.component.DataComponents.CONTAINER);
                }
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                // What the in-game AI agent may do here (ai.enabled, ai.share_replies).
                Qbotics.platform().sendToPlayer(player, qbotics.agent.ServerModel.settings());
                qbotics.display.Tablets.get(player.level().getServer()).forgetSent(player.getUUID());
            }
        });
        // The player's game starts its screens afresh in a new dimension and on respawning: tablets are sent whole again.
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                qbotics.display.Tablets.get(player.level().getServer()).forgetSent(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                qbotics.display.Tablets.get(player.level().getServer()).forgetSent(player.getUUID());
            }
        });
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ComputerManager.get(player.level().getServer()).onPlayerLeft(player);
            }
        });

        Qbotics.init(new QboticsPlatform() {
            @Override
            public void capabilitiesChanged(net.minecraft.world.level.Level level, BlockPos pos) {
                level.invalidateCapabilities(pos);
            }

            @Override
            public void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
                // A connection that can't take it (a test's stand-in player) is skipped, not an error.
                if (player.connection != null && player.connection.hasChannel(payload)) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }

            @Override
            public void sendToServer(CustomPacketPayload payload) {
                ClientPacketDistributor.sendToServer(payload);
            }

            @Override
            public ItemPort itemPort(ServerLevel level, BlockPos pos, Direction side) {
                ResourceHandler<ItemResource> handler = level.getCapability(Capabilities.Item.BLOCK, pos, side);
                return handler == null ? null : new HandlerItemPort(handler);
            }

            @Override
            public qbotics.network.ScreenSessions.Opened openScreenAs(ServerLevel level, BlockPos pos, Direction face, UUID owner, UUID bus) {
                return StandIn.open(level, pos, face, owner, bus);
            }

            @Override
            public net.minecraft.server.level.ServerPlayer standIn(ServerLevel level, UUID owner, UUID plate, BlockPos at, Direction facing) {
                return StandIn.at(level, owner, plate, at, facing);
            }

            @Override
            public ItemPort entityItems(net.minecraft.world.entity.Entity entity) {
                if (entity instanceof net.minecraft.world.entity.player.Player) {
                    return null;
                }
                ResourceHandler<ItemResource> handler = entity.getCapability(Capabilities.Item.ENTITY_AUTOMATION, null);
                if (handler == null) {
                    handler = entity.getCapability(Capabilities.Item.ENTITY);
                }
                return handler == null ? null : new HandlerItemPort(handler);
            }

            @Override
            public void chargeAttack(net.minecraft.server.level.ServerPlayer standIn, int ticks) {
                if (standIn instanceof StandIn player) {
                    player.charge(ticks);
                }
            }

            @Override
            public boolean sameModTeam(java.util.UUID one, java.util.UUID other) {
                return FtbTeams.sameTeam(one, other);
            }

            @Override
            public boolean mayBreak(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
                return !net.neoforged.neoforge.common.CommonHooks.fireBlockBreak(level, net.minecraft.world.level.GameType.SURVIVAL, player, pos,
                        level.getBlockState(pos)).isCanceled();
            }

            @Override
            public boolean placeChecked(ServerLevel level, net.minecraft.server.level.ServerPlayer player, BlockPos pos, Direction face, Runnable place) {
                net.neoforged.neoforge.common.util.BlockSnapshot before = net.neoforged.neoforge.common.util.BlockSnapshot.create(level.dimension(), level, pos);
                place.run();
                if (net.neoforged.neoforge.event.EventHooks.onBlockPlace(player, before, face)) {
                    before.restore(net.minecraft.world.level.block.Block.UPDATE_ALL);
                    return false;
                }
                return true;
            }

            @Override
            public String modName(String modId) {
                return net.neoforged.fml.ModList.get().getModContainerById(modId)
                        .map(mod -> mod.getModInfo().getDisplayName()).orElse(modId);
            }

            @Override
            public java.util.List<String[]> loadedMods() {
                return net.neoforged.fml.ModList.get().getMods().stream()
                        .map(mod -> new String[] {mod.getModId(), mod.getDisplayName(), mod.getVersion().toString(),
                                mod.getDescription() == null ? "" : mod.getDescription()})
                        .sorted(java.util.Comparator.comparing((String[] mod) -> mod[1].toLowerCase(java.util.Locale.ROOT)))
                        .toList();
            }

            @Override
            public qbotics.network.FluidPort fluidPort(ServerLevel level, BlockPos pos, Direction side) {
                var handler = level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
                return handler == null ? null : new MediaHandlers.FluidTarget(handler);
            }

            @Override
            public qbotics.network.EnergyPort energyPort(ServerLevel level, BlockPos pos, Direction side) {
                var handler = level.getCapability(Capabilities.Energy.BLOCK, pos, side);
                return handler == null ? null : new MediaHandlers.EnergyTarget(handler);
            }

            @Override
            public boolean useFluidContainer(net.minecraft.world.entity.player.Player player, net.minecraft.world.InteractionHand hand,
                    net.minecraft.world.level.Level level, BlockPos pos, Direction side) {
                var handler = level.getCapability(Capabilities.Fluid.BLOCK, pos, side);
                if (handler == null) {
                    return false;
                }
                try (var transaction = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                    boolean moved = net.neoforged.neoforge.transfer.fluid.FluidUtil.interactWithFluidHandler(player, hand, pos, handler, transaction);
                    if (moved) {
                        transaction.commit();
                    }
                    return moved;
                }
            }

            @Override
            public boolean isFakePlayer(net.minecraft.world.entity.player.Player player) {
                return player.isFakePlayer();
            }

            @Override
            public void forceChunk(net.minecraft.server.level.ServerLevel level, BlockPos owner, int chunkX, int chunkZ, boolean add) {
                CHUNK_TICKETS.forceChunk(level, owner, chunkX, chunkZ, add, false);
            }

            @Override
            public net.minecraft.world.level.material.Fluid fluidIn(ItemStack stack) {
                if (stack.isEmpty()) {
                    return null;
                }
                var held = net.neoforged.neoforge.transfer.fluid.FluidUtil.getFirstStackContained(stack);
                return held.isEmpty() ? null : held.getFluid();
            }

            @Override
            public boolean exchangeFluid(net.minecraft.world.Container container, int slot, qbotics.block.FluidInterfaceBlockEntity store, int tank) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    return false;
                }
                var items = net.neoforged.neoforge.transfer.item.VanillaContainerWrapper.of(container);
                var item = Capabilities.Fluid.ITEM.getCapability(stack, net.neoforged.neoforge.transfer.access.ItemAccess.forHandlerIndexStrict(items, slot));
                if (item == null) {
                    return false;
                }
                var into = net.neoforged.neoforge.transfer.RangedResourceHandler.ofSingleIndex(MediaHandlers.of(store), tank);
                if (net.neoforged.neoforge.transfer.ResourceHandlerUtil.move(item, into, fluid -> true, Integer.MAX_VALUE, null) > 0) {
                    return true;
                }
                return net.neoforged.neoforge.transfer.ResourceHandlerUtil.move(into, item, fluid -> true, Integer.MAX_VALUE, null) > 0;
            }

            @Override
            public int exchangeEnergy(net.minecraft.world.Container container, int slot, qbotics.block.EnergyInterfaceBlockEntity store, boolean charge, int max) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    return 0;
                }
                var items = net.neoforged.neoforge.transfer.item.VanillaContainerWrapper.of(container);
                var item = Capabilities.Energy.ITEM.getCapability(stack, net.neoforged.neoforge.transfer.access.ItemAccess.forHandlerIndexStrict(items, slot));
                if (item == null) {
                    return 0;
                }
                var cell = MediaHandlers.of(store);
                return charge ? net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil.move(cell, item, max, null)
                        : net.neoforged.neoforge.transfer.energy.EnergyHandlerUtil.move(item, cell, max, null);
            }
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                // The client-side handler is registered in QboticsNeoForgeClient.
                .playToClient(TerminalStatePayload.TYPE, TerminalStatePayload.STREAM_CODEC)
                .playToClient(qbotics.net.TracePayload.TYPE, qbotics.net.TracePayload.STREAM_CODEC)
                .playToClient(IdePayloads.FileResponse.TYPE, IdePayloads.FileResponse.STREAM_CODEC)
                .playToClient(IdePayloads.ScratchResult.TYPE, IdePayloads.ScratchResult.STREAM_CODEC)
                .playToClient(IdePayloads.OpenFile.TYPE, IdePayloads.OpenFile.STREAM_CODEC)
                .playToClient(IdePayloads.FilesChanged.TYPE, IdePayloads.FilesChanged.STREAM_CODEC)
                .playToClient(IdePayloads.OpenEditor.TYPE, IdePayloads.OpenEditor.STREAM_CODEC)
                .playToClient(qbotics.net.DisplayPayloads.Text.TYPE, qbotics.net.DisplayPayloads.Text.STREAM_CODEC)
                .playToClient(qbotics.net.DisplayPayloads.Panel.TYPE, qbotics.net.DisplayPayloads.Panel.STREAM_CODEC)
                .playToClient(qbotics.net.DisplayPayloads.OpenCloseUp.TYPE, qbotics.net.DisplayPayloads.OpenCloseUp.STREAM_CODEC)
                .playToClient(qbotics.net.DisplayPayloads.Web.TYPE, qbotics.net.DisplayPayloads.Web.STREAM_CODEC)
                .playToClient(qbotics.net.TabletPayloads.State.TYPE, qbotics.net.TabletPayloads.State.STREAM_CODEC)
                .playToClient(qbotics.net.TabletPayloads.Open.TYPE, qbotics.net.TabletPayloads.Open.STREAM_CODEC)
                // The in-game AI agent: its tools act as the player who asked; shared questions and answers go to everyone.
                .playToClient(qbotics.net.AgentPayloads.ToolResult.TYPE, qbotics.net.AgentPayloads.ToolResult.STREAM_CODEC)
                .playToClient(qbotics.net.AgentPayloads.Settings.TYPE, qbotics.net.AgentPayloads.Settings.STREAM_CODEC)
                .playToServer(qbotics.net.AgentPayloads.ToolCall.TYPE, qbotics.net.AgentPayloads.ToolCall.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        net.minecraft.server.MinecraftServer server = player.level().getServer();
                        qbotics.agent.AgentTools.call(player, payload.tool(), payload.args()).whenComplete((text, error) -> server.execute(() -> {
                            String answer = error != null ? "The tool failed: " + error.getMessage() : text;
                            if (answer.length() > qbotics.net.AgentPayloads.MAX_TEXT) {
                                answer = answer.substring(0, qbotics.net.AgentPayloads.MAX_TEXT - 20) + "\n...(cut short)";
                            }
                            Qbotics.platform().sendToPlayer(player, new qbotics.net.AgentPayloads.ToolResult(payload.id(), answer));
                        }));
                    }
                })
                .playToClient(qbotics.net.AgentPayloads.ModelReply.TYPE, qbotics.net.AgentPayloads.ModelReply.STREAM_CODEC)
                .playToServer(qbotics.net.AgentPayloads.ModelRequest.TYPE, qbotics.net.AgentPayloads.ModelRequest.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        net.minecraft.server.MinecraftServer server = player.level().getServer();
                        qbotics.agent.ServerModel.relay(player.getUUID(), payload.json()).whenComplete((reply, error) -> server.execute(() -> {
                            String body = error != null ? "{\"error\":{\"message\":\"The server couldn't ask its AI.\"}}" : reply.body();
                            if (body.length() > 1_000_000) {
                                body = "{\"error\":{\"message\":\"The AI's answer was too long.\"}}";
                            }
                            Qbotics.platform().sendToPlayer(player, new qbotics.net.AgentPayloads.ModelReply(payload.id(),
                                    error != null ? 502 : reply.status(), body));
                        }));
                    }
                })
                .playToServer(qbotics.net.AgentPayloads.Share.TYPE, qbotics.net.AgentPayloads.Share.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player && qbotics.QboticsConfig.aiShareReplies) {
                        String name = player.getGameProfile().name();
                        for (net.minecraft.network.chat.Component line : payload.answer()
                                ? qbotics.agent.AgentChat.answer(name, payload.text()) : qbotics.agent.AgentChat.question(name, payload.text())) {
                            player.level().getServer().getPlayerList().broadcastSystemMessage(line, false);
                        }
                    }
                })
                .playToServer(qbotics.net.TabletPayloads.Unpair.TYPE, qbotics.net.TabletPayloads.Unpair.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.display.Tablets.get(player.level().getServer()).unpairTab(player, payload.tablet(), payload.index());
                    }
                })
                .playToServer(qbotics.net.TabletPayloads.Select.TYPE, qbotics.net.TabletPayloads.Select.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.display.Tablets.get(player.level().getServer()).select(player, payload.tablet(), payload.index());
                    }
                })
                .playToServer(qbotics.net.DisplayPayloads.WebClick.TYPE, qbotics.net.DisplayPayloads.WebClick.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.display.Displays.onWebClick(player, payload);
                    }
                })
                .playToServer(qbotics.net.DisplayPayloads.Touch.TYPE, qbotics.net.DisplayPayloads.Touch.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.display.Displays.onCloseUpTouch(player, payload);
                    }
                })
                .playToClient(qbotics.net.BoxPayloads.Info.TYPE, qbotics.net.BoxPayloads.Info.STREAM_CODEC)
                .playToServer(qbotics.net.BoxPayloads.Action.TYPE, qbotics.net.BoxPayloads.Action.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.block.BlockSensorBlockEntity.onAction(player, payload);
                    }
                })
                .playToClient(qbotics.net.SensorPayloads.Info.TYPE, qbotics.net.SensorPayloads.Info.STREAM_CODEC)
                .playToServer(qbotics.net.SensorPayloads.Action.TYPE, qbotics.net.SensorPayloads.Action.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.block.AreaBlockEntity.onAction(player, payload);
                    }
                })
                .playToClient(qbotics.net.XpPayloads.Info.TYPE, qbotics.net.XpPayloads.Info.STREAM_CODEC)
                .playToServer(qbotics.net.XpPayloads.Action.TYPE, qbotics.net.XpPayloads.Action.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.block.ExperienceInterfaceBlockEntity.onAction(player, payload);
                    }
                })
                .playToClient(qbotics.net.ProbePayloads.Info.TYPE, qbotics.net.ProbePayloads.Info.STREAM_CODEC)
                .playToServer(qbotics.net.ProbePayloads.Mode.TYPE, qbotics.net.ProbePayloads.Mode.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.block.ProbeItem.onMode(player, payload);
                    }
                })
                .playToServer(qbotics.net.ProbePayloads.Rename.TYPE, qbotics.net.ProbePayloads.Rename.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.block.ProbeItem.onRename(player, payload);
                    }
                })
                .playToServer(IdePayloads.FileRequest.TYPE, IdePayloads.FileRequest.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ComputerManager.get(player.level().getServer()).onFileRequest(player, payload);
                    }
                })
                .playToServer(IdePayloads.ScratchRun.TYPE, IdePayloads.ScratchRun.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        ComputerManager.get(player.level().getServer()).onScratchRun(player, payload);
                    }
                })
                .playToClient(qbotics.net.GitPayloads.OpenToken.TYPE, qbotics.net.GitPayloads.OpenToken.STREAM_CODEC)
                .playToServer(qbotics.net.GitPayloads.SetToken.TYPE, qbotics.net.GitPayloads.SetToken.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.uplink.GitTokens.set(player.level().getServer(), player.getUUID(), payload.token());
                        player.sendSystemMessage(Component.translatable(payload.token().isBlank()
                                ? "message.qbotics.git_token.removed" : "message.qbotics.git_token.saved"));
                    }
                })
                .playToServer(TerminalInputPayload.TYPE, TerminalInputPayload.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        payload.handle(player);
                    }
                });
    }

    /**
     * Only in the game test runs (see build.gradle): registers the tests in
     * qbotics.test.QboticsGameTests, or, in the mod test run (qbotics.modTests), those in
     * qbotics.test.QboticsModTests, which need real mods beside Qbotics.
     */
    private static void registerGameTests(IEventBus modBus) {
        boolean modded = Boolean.getBoolean("qbotics.modTests");
        java.util.Map<String, Consumer<GameTestHelper>> tests = modded ? qbotics.test.QboticsModTests.ALL : QboticsGameTests.ALL;
        java.util.Map<String, Integer> limits = modded ? qbotics.test.QboticsModTests.TICK_LIMITS : QboticsGameTests.TICK_LIMITS;
        DeferredRegister<Consumer<GameTestHelper>> functions = DeferredRegister.create(Registries.TEST_FUNCTION, Qbotics.MOD_ID);
        tests.forEach((name, body) -> functions.register(name, () -> body));
        functions.register(modBus);
        modBus.addListener((RegisterGameTestsEvent event) -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(Qbotics.id("default"));
            for (String name : tests.keySet()) {
                event.registerTest(Qbotics.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, Qbotics.id(name)),
                        new TestData<>(environment, Qbotics.id("empty"), limits.getOrDefault(name, 1200), 0, true)));
            }
        });
    }

}
