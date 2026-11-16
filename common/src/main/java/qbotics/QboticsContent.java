package qbotics;

import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import qbotics.block.AntennaBlock;
import qbotics.block.AntennaBlockEntity;
import qbotics.block.CableBlock;
import qbotics.block.ComputerBlock;
import qbotics.block.ComputerBlockEntity;
import qbotics.block.InterfaceBlock;
import qbotics.block.InterfaceBlockEntity;
import qbotics.block.PortBlockEntity;

/**
 * Registered game objects. Each loader registers them its own way and fills these in
 * before any of them is used.
 */
public final class QboticsContent {
    /** The advancement trigger the lessons are met by (qbotics.lesson). */
    public static Supplier<qbotics.lesson.LessonTrigger> LESSON_TRIGGER;
    public static Supplier<ComputerBlock> COMPUTER_BLOCK;
    public static Supplier<BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY;
    public static Supplier<CableBlock> CABLE_BLOCK;
    public static Supplier<InterfaceBlock> INTERFACE_BLOCK;
    /** On a Network Probe: which mode it is in (see ProbeItem). */
    public static Supplier<DataComponentType<Integer>> PROBE_MODE;
    /** The Network Probe: reads a device's name and addresses. */
    public static Supplier<? extends net.minecraft.world.item.Item> PROBE_ITEM;
    /** The plates (Serial Buses and world plates), as the items a player holds. They go on cables. */
    public static java.util.Map<String, Supplier<? extends net.minecraft.world.item.Item>> PLATE_ITEMS;

    /** The item for a plate: plateItem(Kind.BUS, Medium.FLUID) is the Fluid Serial Bus. */
    public static net.minecraft.world.item.Item plateItem(qbotics.network.NetworkDevice.Kind kind, qbotics.network.Medium medium) {
        return PLATE_ITEMS.get(medium.prefix + kind.word).get();
    }

    /** The network's stores for fluid and for energy. */
    public static Supplier<qbotics.block.StoreBlock> FLUID_INTERFACE_BLOCK;
    public static Supplier<qbotics.block.StoreBlock> ENERGY_INTERFACE_BLOCK;
    public static Supplier<qbotics.block.StoreBlock> EXPERIENCE_INTERFACE_BLOCK;
    /** The game interfaces: a player's inventory, the chat, and commands (that last one spawned in only). */
    public static Supplier<qbotics.block.GameInterfaceBlock> PLAYER_INTERFACE_BLOCK;
    public static Supplier<qbotics.block.EntitySensorBlock> ENTITY_SENSOR_BLOCK;
    public static Supplier<BlockEntityType<qbotics.block.EntitySensorBlockEntity>> ENTITY_SENSOR_BLOCK_ENTITY;
    public static Supplier<qbotics.block.CollectorBlock> COLLECTOR_BLOCK;
    public static Supplier<BlockEntityType<qbotics.block.CollectorBlockEntity>> COLLECTOR_BLOCK_ENTITY;
    public static Supplier<qbotics.block.BlockSensorBlock> BLOCK_SENSOR_BLOCK;
    public static Supplier<BlockEntityType<qbotics.block.BlockSensorBlockEntity>> BLOCK_SENSOR_BLOCK_ENTITY;
    public static Supplier<qbotics.block.GameInterfaceBlock> CHAT_INTERFACE_BLOCK;
    public static Supplier<qbotics.block.GameInterfaceBlock> COMMAND_INTERFACE_BLOCK;
    public static Supplier<BlockEntityType<qbotics.block.GameInterfaceBlockEntity>> GAME_INTERFACE_BLOCK_ENTITY;
    public static Supplier<BlockEntityType<qbotics.block.ExperienceInterfaceBlockEntity>> EXPERIENCE_INTERFACE_BLOCK_ENTITY;
    /** On an Experience Interface item: the experience it carries, in points. */
    public static Supplier<DataComponentType<Long>> XP_POINTS;
    public static Supplier<BlockEntityType<qbotics.block.FluidInterfaceBlockEntity>> FLUID_INTERFACE_BLOCK_ENTITY;
    public static Supplier<BlockEntityType<qbotics.block.EnergyInterfaceBlockEntity>> ENERGY_INTERFACE_BLOCK_ENTITY;
    public static Supplier<BlockEntityType<InterfaceBlockEntity>> INTERFACE_BLOCK_ENTITY;
    /** On a hard drive: the ID its files are kept under (design doc §17.3). */
    public static Supplier<DataComponentType<UUID>> MEDIA_ID;
    /** The Programmer and its screen (design doc §5.4). */
    public static Supplier<BlockEntityType<qbotics.block.ProgrammerBlockEntity>> PROGRAMMER_BLOCK_ENTITY;
    /** Text Monitors and Control Panels share one block entity (design doc §10). */
    public static Supplier<BlockEntityType<qbotics.block.DisplayBlockEntity>> DISPLAY_BLOCK_ENTITY;
    public static Supplier<qbotics.block.DisplayBlock> TEXT_MONITOR_BLOCK;
    public static Supplier<qbotics.block.DisplayBlock> CONTROL_PANEL_BLOCK;
    public static Supplier<qbotics.block.DisplayBlock> WEB_PANEL_BLOCK;
    /** Qbots (design doc §8): the Iron Qbot, its block entity and screen, and the block holding its part models. */
    public static Supplier<qbotics.qbot.QbotBlock> IRON_QBOT_BLOCK;
    public static Supplier<BlockEntityType<qbotics.qbot.QbotBlockEntity>> QBOT_BLOCK_ENTITY;
    public static Supplier<qbotics.block.ServerBlock> SERVER_BLOCK;
    public static Supplier<BlockEntityType<qbotics.block.ServerBlockEntity>> SERVER_BLOCK_ENTITY;
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.qbot.QbotMenu>> QBOT_MENU;
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.laptop.LaptopMenu>> LAPTOP_MENU;
    public static Supplier<? extends net.minecraft.world.item.Item> LAPTOP_ITEM;
    public static Supplier<net.minecraft.world.level.block.entity.BlockEntityType<qbotics.uplink.UplinkBlockEntity>> UPLINK_BLOCK_ENTITY;
    public static Supplier<? extends net.minecraft.world.level.block.Block> UPLINK_BLOCK;
    public static Supplier<qbotics.qbot.QbotPartBlock> QBOT_PART_BLOCK;
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.block.ProgrammerMenu>> PROGRAMMER_MENU;
    /** An antenna's screen: its upgrades and chunk loading. */
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.block.AntennaMenu>> ANTENNA_MENU;
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.block.ServerMenu>> SERVER_MENU;
    /** The Drive Bay, which hard drives go in, and its screen (design doc §17.6). */
    public static Supplier<BlockEntityType<qbotics.block.DriveBayBlockEntity>> DRIVE_BAY_BLOCK_ENTITY;
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.block.DriveBayMenu>> DRIVE_BAY_MENU;
    /** The Fluid Interface's screen (tanks, filters, bucket slots) and the Energy Interface's (gauge, battery slots). */
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.block.FluidInterfaceMenu>> FLUID_INTERFACE_MENU;
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.block.EnergyInterfaceMenu>> ENERGY_INTERFACE_MENU;
    /** The Input block's screen of filters and slots. */
    public static Supplier<net.minecraft.world.inventory.MenuType<qbotics.block.InterfaceMenu>> INTERFACE_MENU;
    /** A cable's plate: an input, an output or a bus. */
    public static Supplier<BlockEntityType<PortBlockEntity>> PORT_BLOCK_ENTITY;
    public static Supplier<AntennaBlock> ANTENNA_SHORT_BLOCK;
    public static Supplier<AntennaBlock> ANTENNA_LONG_BLOCK;
    public static Supplier<AntennaBlock> ANTENNA_INFINITE_BLOCK;
    /** Shared by the three antenna tiers. */
    public static Supplier<BlockEntityType<AntennaBlockEntity>> ANTENNA_BLOCK_ENTITY;
    /** On a computer item: which drive (folder of files) it carries. */
    public static Supplier<DataComponentType<UUID>> DRIVE_ID;
    /** A tablet (design doc §10.6), and the ID its screens are kept under. */
    public static Supplier<? extends net.minecraft.world.item.Item> TABLET_ITEM;
    public static Supplier<DataComponentType<UUID>> TABLET_ID;

    private QboticsContent() {
    }
}
