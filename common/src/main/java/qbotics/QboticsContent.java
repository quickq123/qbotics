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
    public static Supplier<ComputerBlock> COMPUTER_BLOCK;
    public static Supplier<BlockEntityType<ComputerBlockEntity>> COMPUTER_BLOCK_ENTITY;
    public static Supplier<CableBlock> CABLE_BLOCK;
    public static Supplier<InterfaceBlock> INTERFACE_BLOCK;
    /** The Network Probe: reads a device's name and addresses. */
    public static Supplier<? extends net.minecraft.world.item.Item> PROBE_ITEM;
    /** The Input, Output and Bus plates, as the items a player holds. They go on cables. */
    public static java.util.Map<qbotics.network.NetworkDevice.Kind, Supplier<? extends net.minecraft.world.item.Item>> PLATE_ITEMS;
    public static Supplier<BlockEntityType<InterfaceBlockEntity>> INTERFACE_BLOCK_ENTITY;
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

    private QboticsContent() {
    }
}
