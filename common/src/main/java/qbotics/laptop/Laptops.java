package qbotics.laptop;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsConfig;
import qbotics.QboticsContent;
import qbotics.computer.ComputerManager;
import qbotics.computer.ServerComputer;
import qbotics.engine.HostFileSystem;

/**
 * Laptops (design doc §5.6). Each player has one laptop computer: one shell, one set of files,
 * whichever laptop item they open. Its files are kept for the player, so a new laptop opens
 * onto the same files, and two laptops never work on two copies of them.
 *
 * <p>It runs while its player is online and carries a laptop; otherwise what it is running is
 * held (paused) until they are back with one, so an absent or banned player's programs do no work.
 * The hard drives in the laptop being carried appear at /net/disks/disk1 and /net/disks/disk2.
 */
public final class Laptops {
    private Laptops() {
    }

    /** How many hard drives a laptop holds. */
    public static final int DISK_SLOTS = 2;
    /** Where those drives appear: /net/disks/disk1 and /net/disks/disk2. */
    public static final String DISKS_FOLDER = "disks";

    /** The device identity of a player's laptop computer: the same every time. */
    public static UUID deviceId(UUID player) {
        return UUID.nameUUIDFromBytes(("qbotics:laptop:" + player).getBytes(StandardCharsets.UTF_8));
    }

    /** The drive a player's laptop files are kept on. */
    public static UUID driveId(UUID player) {
        return UUID.nameUUIDFromBytes(("qbotics:laptop-drive:" + player).getBytes(StandardCharsets.UTF_8));
    }

    /** True if this is a laptop computer, and the given player's. */
    public static boolean isLaptopOf(UUID deviceId, UUID player) {
        return deviceId.equals(deviceId(player));
    }

    /** The laptop a player carries (main hand first, then the rest of their inventory), or empty. */
    public static ItemStack carried(ServerPlayer player) {
        if (player.getMainHandItem().getItem() instanceof LaptopItem) {
            return player.getMainHandItem();
        }
        if (player.getOffhandItem().getItem() instanceof LaptopItem) {
            return player.getOffhandItem();
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack.getItem() instanceof LaptopItem) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    /** True if the player still has this very laptop (the same stack) somewhere on them. */
    public static boolean carriedStack(net.minecraft.world.entity.player.Player player, ItemStack laptop) {
        if (player.getMainHandItem() == laptop || player.getOffhandItem() == laptop) {
            return true;
        }
        for (ItemStack stack : player.getInventory().getNonEquipmentItems()) {
            if (stack == laptop) {
                return true;
            }
        }
        return false;
    }

    /** A player opens a laptop: their laptop computer starts if it isn't running, and its screen opens. */
    public static void open(ServerPlayer player, ItemStack laptop) {
        if (!QboticsConfig.laptopsEnabled) {
            player.sendOverlayMessage(Component.translatable("message.qbotics.laptop.disabled"));
            return;
        }
        ComputerManager manager = ComputerManager.get(player.level().getServer());
        ServerComputer computer = manager.getOrStartLaptop(player);
        importFiles(manager, computer, laptop);
        update(player, computer, laptop);
        manager.openLaptopScreen(player);
    }

    /**
     * A laptop crafted from a computer brings that computer's files along: the first time it is
     * opened they are copied into the player's laptop files, under /imported/, so nothing there is
     * overwritten.
     */
    private static void importFiles(ComputerManager manager, ServerComputer computer, ItemStack laptop) {
        UUID drive = laptop.get(QboticsContent.DRIVE_ID.get());
        if (drive == null) {
            return;
        }
        laptop.remove(QboticsContent.DRIVE_ID.get());
        HostFileSystem from = new qbotics.engine.DriveFileSystem(manager.driveFolder(drive), 1024 * 1024);
        String folder = "/imported/computer-" + drive.toString().substring(0, 8);
        try {
            HostFileSystem to = computer.drive();
            try {
                to.call(HostFileSystem.MKDIR, "/imported", new byte[0]);
            } catch (HostFileSystem.FsException exists) {
                // already there
            }
            to.call(HostFileSystem.MKDIR, folder, new byte[0]);
            qbotics.block.Programmers.copyTree(from, "/", to, folder);
        } catch (HostFileSystem.FsException e) {
            qbotics.Qbotics.LOGGER.warn("Copying a computer's files into a laptop stopped (error {})", e.errno);
        }
    }

    /** The hard drives in a laptop, by slot. */
    public static ItemContainerContents disks(ItemStack laptop) {
        return laptop.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY);
    }

    /**
     * Brings one player's laptop computer up to date: held or running, and which drives it sees.
     *
     * @param laptop the laptop they carry, or empty
     */
    private static void update(@Nullable ServerPlayer player, ServerComputer computer, ItemStack laptop) {
        boolean running = player != null && !laptop.isEmpty() && QboticsConfig.laptopsEnabled;
        computer.setHeld(!running);
        Map<String, HostFileSystem> drives = new LinkedHashMap<>();
        if (running) {
            ComputerManager manager = ComputerManager.get(player.level().getServer());
            var stacks = disks(laptop).allItemsCopyStream().toList();
            for (int slot = 0; slot < Math.min(DISK_SLOTS, stacks.size()); slot++) {
                UUID media = stacks.get(slot).get(QboticsContent.MEDIA_ID.get());
                if (media != null) {
                    drives.put("disk" + (slot + 1), manager.mediaFiles(media));
                }
            }
        }
        computer.setMounts(drives.isEmpty() ? Map.of() : Map.of(DISKS_FOLDER, drives));
    }

    /** Brings this player's laptop computer up to date now (held or not, its drives), if it is running. */
    public static void refresh(ServerPlayer player) {
        ServerComputer computer = ComputerManager.get(player.level().getServer()).laptops().get(player.getUUID());
        if (computer != null) {
            update(player, computer, carried(player));
        }
    }

    /** Twice a second: every laptop computer is held or let go, and sees the drives in the laptop its player carries. */
    public static void tick(MinecraftServer server) {
        if (server.getTickCount() % 10 != 0) {
            return;
        }
        ComputerManager manager = ComputerManager.get(server);
        for (Map.Entry<UUID, ServerComputer> entry : manager.laptops().entrySet()) {
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            update(player, entry.getValue(), player == null ? ItemStack.EMPTY : carried(player));
        }
    }
}
