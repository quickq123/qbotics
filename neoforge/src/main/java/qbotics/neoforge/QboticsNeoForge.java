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
    // Qbots (design doc §8): drawn by their renderer, so they can step, turn and swing.
    private static final DeferredBlock<qbotics.qbot.QbotBlock> IRON_QBOT = BLOCKS.registerBlock("iron_qbot", qbotics.qbot.QbotBlock::iron,
            properties -> properties.mapColor(MapColor.METAL).strength(2.5f).sound(SoundType.METAL).noOcclusion());
    private static final DeferredItem<BlockItem> IRON_QBOT_ITEM = ITEMS.registerSimpleBlockItem(IRON_QBOT);
    private static final DeferredBlock<qbotics.qbot.QbotPartBlock> QBOT_PART = BLOCKS.registerBlock("qbot_part", qbotics.qbot.QbotPartBlock::new,
            properties -> properties.noOcclusion().noLootTable());
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.qbot.QbotBlockEntity>> QBOT_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("qbot", () -> new BlockEntityType<>(qbotics.qbot.QbotBlockEntity::new, IRON_QBOT.get()));
    // Upgrades (fitted to antennas; later to Qbots).
    private static final DeferredItem<qbotics.upgrade.UpgradeItem> TRANSPORT_UPGRADE = ITEMS.registerItem("transport_upgrade",
            properties -> new qbotics.upgrade.UpgradeItem(qbotics.upgrade.UpgradeItem.Kind.TRANSPORT, properties.stacksTo(1)));
    private static final DeferredItem<qbotics.upgrade.UpgradeItem> CHUNK_LOADER_UPGRADE = ITEMS.registerItem("chunk_loader_upgrade",
            properties -> new qbotics.upgrade.UpgradeItem(qbotics.upgrade.UpgradeItem.Kind.CHUNK_LOADER, properties.stacksTo(1)));
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
    private static final DeferredItem<BlockItem> ENERGY_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(ENERGY_INTERFACE);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.FluidInterfaceBlockEntity>> FLUID_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("fluid_interface",
                    () -> new BlockEntityType<>(qbotics.block.FluidInterfaceBlockEntity::new, FLUID_INTERFACE.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.EnergyInterfaceBlockEntity>> ENERGY_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("energy_interface",
                    () -> new BlockEntityType<>(qbotics.block.EnergyInterfaceBlockEntity::new, ENERGY_INTERFACE.get()));
    static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.DisplayBlockEntity>> DISPLAY_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("display", () -> new BlockEntityType<>(qbotics.block.DisplayBlockEntity::new, TEXT_MONITOR.get(), CONTROL_PANEL.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.ProgrammerBlockEntity>> PROGRAMMER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("programmer", () -> new BlockEntityType<>(qbotics.block.ProgrammerBlockEntity::new, PROGRAMMER.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.DriveBayBlockEntity>> DRIVE_BAY_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("drive_bay", () -> new BlockEntityType<>(qbotics.block.DriveBayBlockEntity::new, DRIVE_BAY.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<InterfaceBlockEntity>> INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("interface", () -> new BlockEntityType<>(InterfaceBlockEntity::new, INTERFACE.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PortBlockEntity>> PORT_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("port", () -> new BlockEntityType<>(PortBlockEntity::new, CABLE.get()));

    // Parts: made in the crafting grid, used up in other recipes.
    private static final DeferredItem<net.minecraft.world.item.Item> SCREEN_ITEM = ITEMS.registerSimpleItem("screen");
    private static final DeferredItem<net.minecraft.world.item.Item> MOTHERBOARD_ITEM = ITEMS.registerSimpleItem("motherboard");
    private static final DeferredItem<qbotics.block.ProbeItem> PROBE_ITEM = ITEMS.registerItem("probe",
            properties -> new qbotics.block.ProbeItem(properties.stacksTo(1)));

    private static final DeferredRegister<net.minecraft.world.inventory.MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, Qbotics.MOD_ID);
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.InterfaceMenu>> INTERFACE_MENU =
            MENUS.register("interface", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.InterfaceMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.qbot.QbotMenu>> QBOT_MENU =
            MENUS.register("qbot", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.qbot.QbotMenu(containerId, inventory)));
    static final DeferredHolder<net.minecraft.world.inventory.MenuType<?>, net.minecraft.world.inventory.MenuType<qbotics.block.AntennaMenu>> ANTENNA_MENU =
            MENUS.register("antenna", () -> net.neoforged.neoforge.common.extensions.IMenuTypeExtension.create(
                    (containerId, inventory, data) -> new qbotics.block.AntennaMenu(containerId, inventory)));
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
                        output.accept(BUS_ITEM.get());
                        output.accept(FLUID_BUS_ITEM.get());
                        output.accept(ENERGY_BUS_ITEM.get());
                        output.accept(USB_ITEM.get());
                        output.accept(PIN_ITEM.get());
                        output.accept(PLACER_ITEM.get());
                        output.accept(BREAKER_ITEM.get());
                        output.accept(ANTENNA_SHORT_ITEM.get());
                        output.accept(ANTENNA_LONG_ITEM.get());
                        output.accept(ANTENNA_INFINITE_ITEM.get());
                        output.accept(PROBE_ITEM.get());
                        output.accept(DRIVE_BAY_ITEM.get());
                        output.accept(HARD_DRIVE_ITEM.get());
                        output.accept(PROGRAMMER_ITEM.get());
                        output.accept(TEXT_MONITOR_ITEM.get());
                        output.accept(CONTROL_PANEL_ITEM.get());
                        output.accept(IRON_QBOT_ITEM.get());
                        output.accept(TRANSPORT_UPGRADE.get());
                        output.accept(CHUNK_LOADER_UPGRADE.get());
                        output.accept(SCREEN_ITEM.get());
                        output.accept(MOTHERBOARD_ITEM.get());
                    })
                    .build());

    private static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Qbotics.MOD_ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> DRIVE_ID =
            DATA_COMPONENTS.registerComponentType("drive_id",
                    builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));
    /** On a hard drive: the ID its files are kept under. */
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
        container.registerConfig(net.neoforged.fml.config.ModConfig.Type.SERVER, QboticsServerConfig.SPEC);
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Loading event) -> QboticsServerConfig.apply(event));
        modBus.addListener((net.neoforged.fml.event.config.ModConfigEvent.Reloading event) -> QboticsServerConfig.apply(event));
        QboticsContent.COMPUTER_BLOCK = COMPUTER;
        QboticsContent.COMPUTER_BLOCK_ENTITY = COMPUTER_BLOCK_ENTITY;
        QboticsContent.CABLE_BLOCK = CABLE;
        QboticsContent.DRIVE_ID = DRIVE_ID;
        QboticsContent.MEDIA_ID = MEDIA_ID;
        QboticsContent.DRIVE_BAY_BLOCK_ENTITY = DRIVE_BAY_BLOCK_ENTITY;
        QboticsContent.PROGRAMMER_BLOCK_ENTITY = PROGRAMMER_BLOCK_ENTITY;
        QboticsContent.DISPLAY_BLOCK_ENTITY = DISPLAY_BLOCK_ENTITY;
        QboticsContent.TEXT_MONITOR_BLOCK = TEXT_MONITOR;
        QboticsContent.CONTROL_PANEL_BLOCK = CONTROL_PANEL;
        QboticsContent.PROGRAMMER_MENU = PROGRAMMER_MENU;
        QboticsContent.ANTENNA_MENU = ANTENNA_MENU;
        QboticsContent.IRON_QBOT_BLOCK = IRON_QBOT;
        QboticsContent.QBOT_BLOCK_ENTITY = QBOT_BLOCK_ENTITY;
        QboticsContent.QBOT_MENU = QBOT_MENU;
        QboticsContent.QBOT_PART_BLOCK = QBOT_PART;
        QboticsContent.DRIVE_BAY_MENU = DRIVE_BAY_MENU;
        QboticsContent.PROBE_MODE = PROBE_MODE;
        QboticsContent.INTERFACE_BLOCK = INTERFACE;
        QboticsContent.PROBE_ITEM = PROBE_ITEM;
        QboticsContent.PLATE_ITEMS = java.util.Map.ofEntries(
                java.util.Map.entry("pin", PIN_ITEM),
                java.util.Map.entry("placer", PLACER_ITEM),
                java.util.Map.entry("breaker", BREAKER_ITEM),
                java.util.Map.entry("bus", BUS_ITEM),
                java.util.Map.entry("fluid_bus", FLUID_BUS_ITEM),
                java.util.Map.entry("energy_bus", ENERGY_BUS_ITEM),
                java.util.Map.entry("universal_bus", USB_ITEM));
        QboticsContent.FLUID_INTERFACE_BLOCK = FLUID_INTERFACE;
        QboticsContent.ENERGY_INTERFACE_BLOCK = ENERGY_INTERFACE;
        QboticsContent.EXPERIENCE_INTERFACE_BLOCK = EXPERIENCE_INTERFACE;
        QboticsContent.EXPERIENCE_INTERFACE_BLOCK_ENTITY = EXPERIENCE_INTERFACE_BLOCK_ENTITY;
        QboticsContent.XP_POINTS = XP_POINTS;
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
        // Hoppers and other mods' pipes see the Input block as an ordinary (filtered) inventory.
        modBus.addListener((RegisterCapabilitiesEvent event) -> {
            event.registerBlockEntity(Capabilities.Item.BLOCK, INTERFACE_BLOCK_ENTITY.get(), (input, side) -> VanillaContainerWrapper.of(input));
            // The fluid and energy stores are ordinary tanks and batteries to other mods' pipes and cables.
            event.registerBlockEntity(Capabilities.Fluid.BLOCK, FLUID_INTERFACE_BLOCK_ENTITY.get(), (store, side) -> MediaHandlers.of(store));
            event.registerBlockEntity(Capabilities.Energy.BLOCK, ENERGY_INTERFACE_BLOCK_ENTITY.get(), (store, side) -> MediaHandlers.of(store));
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
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) -> {
            ComputerManager.shutDown(event.getServer());
            qbotics.upgrade.ChunkLoaders.clear();
            qbotics.network.Tunnels.clear();
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
                PacketDistributor.sendToPlayer(player, payload);
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
                .playToClient(IdePayloads.FileResponse.TYPE, IdePayloads.FileResponse.STREAM_CODEC)
                .playToClient(IdePayloads.ScratchResult.TYPE, IdePayloads.ScratchResult.STREAM_CODEC)
                .playToClient(IdePayloads.OpenFile.TYPE, IdePayloads.OpenFile.STREAM_CODEC)
                .playToClient(IdePayloads.OpenEditor.TYPE, IdePayloads.OpenEditor.STREAM_CODEC)
                .playToClient(qbotics.net.DisplayPayloads.Text.TYPE, qbotics.net.DisplayPayloads.Text.STREAM_CODEC)
                .playToClient(qbotics.net.DisplayPayloads.Panel.TYPE, qbotics.net.DisplayPayloads.Panel.STREAM_CODEC)
                .playToClient(qbotics.net.DisplayPayloads.OpenCloseUp.TYPE, qbotics.net.DisplayPayloads.OpenCloseUp.STREAM_CODEC)
                .playToServer(qbotics.net.DisplayPayloads.Touch.TYPE, qbotics.net.DisplayPayloads.Touch.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        qbotics.display.Displays.onCloseUpTouch(player, payload);
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
                .playToServer(TerminalInputPayload.TYPE, TerminalInputPayload.STREAM_CODEC, (payload, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        payload.handle(player);
                    }
                });
    }

    /** Only in the game test run (see build.gradle): registers the tests in qbotics.test.QboticsGameTests. */
    private static void registerGameTests(IEventBus modBus) {
        DeferredRegister<Consumer<GameTestHelper>> functions = DeferredRegister.create(Registries.TEST_FUNCTION, Qbotics.MOD_ID);
        QboticsGameTests.ALL.forEach((name, body) -> functions.register(name, () -> body));
        functions.register(modBus);
        modBus.addListener((RegisterGameTestsEvent event) -> {
            Holder<TestEnvironmentDefinition<?>> environment = event.registerEnvironment(Qbotics.id("default"));
            for (String name : QboticsGameTests.ALL.keySet()) {
                event.registerTest(Qbotics.id(name), new FunctionGameTestInstance(
                        ResourceKey.create(Registries.TEST_FUNCTION, Qbotics.id(name)),
                        new TestData<>(environment, Qbotics.id("empty"), QboticsGameTests.TICK_LIMITS.getOrDefault(name, 1200), 0, true)));
            }
        });
    }

}
