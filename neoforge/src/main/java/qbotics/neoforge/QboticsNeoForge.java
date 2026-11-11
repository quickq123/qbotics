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
            properties -> properties.mapColor(MapColor.METAL).strength(0.5f).sound(SoundType.METAL).noOcclusion());
    private static final DeferredItem<BlockItem> CABLE_ITEM = ITEMS.registerSimpleBlockItem(CABLE);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("computer", () -> new BlockEntityType<>(ComputerBlockEntity::new, COMPUTER.get()));

    // The item blocks (design doc §11.0).
    private static final DeferredBlock<InterfaceBlock> INTERFACE = BLOCKS.registerBlock("interface", InterfaceBlock::new,
            properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(INTERFACE);
    // Plates that go on cables: no blocks of their own.
    private static final DeferredItem<PlateItem> INPUT_ITEM = ITEMS.registerItem("input",
            properties -> new PlateItem(NetworkDevice.Kind.INPUT, qbotics.network.Medium.ITEM, properties));
    private static final DeferredItem<PlateItem> OUTPUT_ITEM = ITEMS.registerItem("output",
            properties -> new PlateItem(NetworkDevice.Kind.OUTPUT, qbotics.network.Medium.ITEM, properties));
    private static final DeferredItem<PlateItem> BUS_ITEM = ITEMS.registerItem("bus",
            properties -> new PlateItem(NetworkDevice.Kind.BUS, qbotics.network.Medium.ITEM, properties));
    private static final DeferredItem<PlateItem> FLUID_INPUT_ITEM = ITEMS.registerItem("fluid_input",
            properties -> new PlateItem(NetworkDevice.Kind.INPUT, qbotics.network.Medium.FLUID, properties));
    private static final DeferredItem<PlateItem> FLUID_OUTPUT_ITEM = ITEMS.registerItem("fluid_output",
            properties -> new PlateItem(NetworkDevice.Kind.OUTPUT, qbotics.network.Medium.FLUID, properties));
    private static final DeferredItem<PlateItem> FLUID_BUS_ITEM = ITEMS.registerItem("fluid_bus",
            properties -> new PlateItem(NetworkDevice.Kind.BUS, qbotics.network.Medium.FLUID, properties));
    private static final DeferredItem<PlateItem> ENERGY_INPUT_ITEM = ITEMS.registerItem("energy_input",
            properties -> new PlateItem(NetworkDevice.Kind.INPUT, qbotics.network.Medium.ENERGY, properties));
    private static final DeferredItem<PlateItem> ENERGY_OUTPUT_ITEM = ITEMS.registerItem("energy_output",
            properties -> new PlateItem(NetworkDevice.Kind.OUTPUT, qbotics.network.Medium.ENERGY, properties));
    private static final DeferredItem<PlateItem> ENERGY_BUS_ITEM = ITEMS.registerItem("energy_bus",
            properties -> new PlateItem(NetworkDevice.Kind.BUS, qbotics.network.Medium.ENERGY, properties));

    // The stores for fluid and energy: full blocks, like the Interface.
    private static final DeferredBlock<qbotics.block.StoreBlock> FLUID_INTERFACE = BLOCKS.registerBlock("fluid_interface",
            qbotics.block.StoreBlock::fluid, properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredBlock<qbotics.block.StoreBlock> ENERGY_INTERFACE = BLOCKS.registerBlock("energy_interface",
            qbotics.block.StoreBlock::energy, properties -> properties.mapColor(MapColor.METAL).strength(2.0f).sound(SoundType.METAL));
    private static final DeferredItem<BlockItem> FLUID_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(FLUID_INTERFACE);
    private static final DeferredItem<BlockItem> ENERGY_INTERFACE_ITEM = ITEMS.registerSimpleBlockItem(ENERGY_INTERFACE);
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.FluidInterfaceBlockEntity>> FLUID_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("fluid_interface",
                    () -> new BlockEntityType<>(qbotics.block.FluidInterfaceBlockEntity::new, FLUID_INTERFACE.get()));
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<qbotics.block.EnergyInterfaceBlockEntity>> ENERGY_INTERFACE_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("energy_interface",
                    () -> new BlockEntityType<>(qbotics.block.EnergyInterfaceBlockEntity::new, ENERGY_INTERFACE.get()));
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
                        output.accept(INPUT_ITEM.get());
                        output.accept(OUTPUT_ITEM.get());
                        output.accept(BUS_ITEM.get());
                        output.accept(FLUID_INPUT_ITEM.get());
                        output.accept(FLUID_OUTPUT_ITEM.get());
                        output.accept(FLUID_BUS_ITEM.get());
                        output.accept(ENERGY_INPUT_ITEM.get());
                        output.accept(ENERGY_OUTPUT_ITEM.get());
                        output.accept(ENERGY_BUS_ITEM.get());
                        output.accept(ANTENNA_SHORT_ITEM.get());
                        output.accept(ANTENNA_LONG_ITEM.get());
                        output.accept(ANTENNA_INFINITE_ITEM.get());
                        output.accept(PROBE_ITEM.get());
                        output.accept(SCREEN_ITEM.get());
                        output.accept(MOTHERBOARD_ITEM.get());
                    })
                    .build());

    private static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Qbotics.MOD_ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> DRIVE_ID =
            DATA_COMPONENTS.registerComponentType("drive_id",
                    builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    static CableBlock cableBlock() {
        return CABLE.get();
    }

    private static final DeferredHolder<DataComponentType<?>, DataComponentType<Integer>> PROBE_MODE =
            DATA_COMPONENTS.registerComponentType("probe_mode", builder -> builder
                    .persistent(com.mojang.serialization.Codec.INT).networkSynchronized(net.minecraft.network.codec.ByteBufCodecs.VAR_INT));

    public QboticsNeoForge(IEventBus modBus, ModContainer container) {
        QboticsContent.COMPUTER_BLOCK = COMPUTER;
        QboticsContent.COMPUTER_BLOCK_ENTITY = COMPUTER_BLOCK_ENTITY;
        QboticsContent.CABLE_BLOCK = CABLE;
        QboticsContent.DRIVE_ID = DRIVE_ID;
        QboticsContent.PROBE_MODE = PROBE_MODE;
        QboticsContent.INTERFACE_BLOCK = INTERFACE;
        QboticsContent.PROBE_ITEM = PROBE_ITEM;
        QboticsContent.PLATE_ITEMS = java.util.Map.ofEntries(
                java.util.Map.entry("input", INPUT_ITEM),
                java.util.Map.entry("output", OUTPUT_ITEM),
                java.util.Map.entry("bus", BUS_ITEM),
                java.util.Map.entry("fluid_input", FLUID_INPUT_ITEM),
                java.util.Map.entry("fluid_output", FLUID_OUTPUT_ITEM),
                java.util.Map.entry("fluid_bus", FLUID_BUS_ITEM),
                java.util.Map.entry("energy_input", ENERGY_INPUT_ITEM),
                java.util.Map.entry("energy_output", ENERGY_OUTPUT_ITEM),
                java.util.Map.entry("energy_bus", ENERGY_BUS_ITEM));
        QboticsContent.FLUID_INTERFACE_BLOCK = FLUID_INTERFACE;
        QboticsContent.ENERGY_INTERFACE_BLOCK = ENERGY_INTERFACE;
        QboticsContent.FLUID_INTERFACE_BLOCK_ENTITY = FLUID_INTERFACE_BLOCK_ENTITY;
        QboticsContent.ENERGY_INTERFACE_BLOCK_ENTITY = ENERGY_INTERFACE_BLOCK_ENTITY;
        QboticsContent.INTERFACE_BLOCK_ENTITY = INTERFACE_BLOCK_ENTITY;
        QboticsContent.INTERFACE_MENU = INTERFACE_MENU;
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
        });
        if (Boolean.getBoolean("qbotics.gameTests")) {
            registerGameTests(modBus);
        }

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) ->
                ComputerManager.get(event.getServer()).tick(event.getServer()));
        NeoForge.EVENT_BUS.addListener((ServerStoppingEvent event) ->
                ComputerManager.shutDown(event.getServer()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                ComputerManager.get(player.level().getServer()).onPlayerLeft(player);
            }
        });

        Qbotics.init(new QboticsPlatform() {
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
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                // The client-side handler is registered in QboticsNeoForgeClient.
                .playToClient(TerminalStatePayload.TYPE, TerminalStatePayload.STREAM_CODEC)
                .playToClient(IdePayloads.FileResponse.TYPE, IdePayloads.FileResponse.STREAM_CODEC)
                .playToClient(IdePayloads.ScratchResult.TYPE, IdePayloads.ScratchResult.STREAM_CODEC)
                .playToClient(IdePayloads.OpenFile.TYPE, IdePayloads.OpenFile.STREAM_CODEC)
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
                        new TestData<>(environment, Qbotics.id("empty"), 1200, 0, true)));
            }
        });
    }

}
