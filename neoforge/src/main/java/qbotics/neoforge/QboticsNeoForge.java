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
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
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
import qbotics.block.ComputerBlock;
import qbotics.block.ComputerBlockEntity;
import qbotics.computer.ComputerManager;
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
    private static final DeferredHolder<BlockEntityType<?>, BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("computer", () -> new BlockEntityType<>(ComputerBlockEntity::new, COMPUTER.get()));

    private static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, Qbotics.MOD_ID);
    private static final DeferredHolder<DataComponentType<?>, DataComponentType<UUID>> DRIVE_ID =
            DATA_COMPONENTS.registerComponentType("drive_id",
                    builder -> builder.persistent(UUIDUtil.CODEC).networkSynchronized(UUIDUtil.STREAM_CODEC));

    public QboticsNeoForge(IEventBus modBus, ModContainer container) {
        QboticsContent.COMPUTER_BLOCK = COMPUTER;
        QboticsContent.COMPUTER_BLOCK_ENTITY = COMPUTER_BLOCK_ENTITY;
        QboticsContent.DRIVE_ID = DRIVE_ID;

        DATA_COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITY_TYPES.register(modBus);
        modBus.addListener(QboticsNeoForge::registerPayloads);
        modBus.addListener(QboticsNeoForge::addToCreativeTabs);
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
        });
    }

    private static void registerPayloads(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                // The client-side handler is registered in QboticsNeoForgeClient.
                .playToClient(TerminalStatePayload.TYPE, TerminalStatePayload.STREAM_CODEC)
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
                        new TestData<>(environment, Qbotics.id("empty"), 200, 0, true)));
            }
        });
    }

    private static void addToCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS || event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(COMPUTER_ITEM);
        }
    }
}
