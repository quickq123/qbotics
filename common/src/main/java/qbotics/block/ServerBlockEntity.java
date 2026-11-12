package qbotics.block;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.Addresses;

/**
 * The Server (design doc §3.4): the late-game computer. A computer in every way (terminal,
 * editor, network, ssh), with 2 MB of memory, an 8 MB drive, an uplink of its own, and four
 * sockets for hard drives, which it sees at /net/disks. Counted apart from computers
 * ({@code servers_per_player}), since each one holds more of the server's memory.
 *
 * <p>Like an antenna it takes a Chunk Loader Upgrade, which keeps its cable network's chunks
 * loaded under the same settings and limits (see ChunkLoaders).
 */
public class ServerBlockEntity extends ComputerBlockEntity implements MenuProvider {
    public static final int SLOTS = 4;
    public static final int HEAP_BYTES = 2 * 1024 * 1024;
    public static final long DRIVE_BYTES = 8L * 1024 * 1024;

    private final SimpleContainer drives = new SimpleContainer(SLOTS) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return stack.getItem() instanceof HardDriveItem;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            ServerBlockEntity.this.drivesChanged();
        }
    };

    /** Its Chunk Loader Upgrade, if it has one. */
    private final SimpleContainer upgrades = new SimpleContainer(1) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return qbotics.upgrade.UpgradeItem.kindOf(stack) == qbotics.upgrade.UpgradeItem.Kind.CHUNK_LOADER;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            ServerBlockEntity.this.setChanged();
            qbotics.upgrade.ChunkLoaders.changed();
        }
    };
    /** Whether its Chunk Loader Upgrade is switched on (it is, until switched off). */
    private boolean chunkLoading = true;
    /** What its chunk loading is doing now: worked out by ChunkLoaders, not saved. */
    private int loaderStatus;
    private int loadedChunks;
    private int wantedChunks;

    /** The Servers loaded now, for chunk loading. Server only. */
    private static final java.util.Set<ServerBlockEntity> LOADED = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    public SimpleContainer upgrades() {
        return upgrades;
    }

    public boolean hasChunkLoader() {
        return qbotics.upgrade.UpgradeItem.kindOf(upgrades.getItem(0)) == qbotics.upgrade.UpgradeItem.Kind.CHUNK_LOADER;
    }

    public boolean chunkLoading() {
        return chunkLoading;
    }

    public void setChunkLoading(boolean on) {
        if (chunkLoading != on) {
            chunkLoading = on;
            setChanged();
            qbotics.upgrade.ChunkLoaders.changed();
        }
    }

    public int loaderStatus() {
        return loaderStatus;
    }

    public int loadedChunks() {
        return loadedChunks;
    }

    public int wantedChunks() {
        return wantedChunks;
    }

    /** Set by ChunkLoaders. */
    public void setLoaderState(int status, int loaded, int wanted) {
        loaderStatus = status;
        loadedChunks = loaded;
        wantedChunks = wanted;
    }

    /** The Servers loaded now (server side). */
    public static java.util.List<ServerBlockEntity> loaded() {
        java.util.List<ServerBlockEntity> list = new java.util.ArrayList<>();
        for (ServerBlockEntity server : LOADED) {
            if (!server.isRemoved()) {
                list.add(server);
            }
        }
        return list;
    }

    @Override
    public void setLevel(net.minecraft.world.level.Level newLevel) {
        super.setLevel(newLevel);
        if (!newLevel.isClientSide()) {
            LOADED.add(this);
            qbotics.upgrade.ChunkLoaders.changed();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        LOADED.remove(this);
    }

    public ServerBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.SERVER_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public String limitType() {
        return qbotics.limits.Limits.SERVER;
    }

    public SimpleContainer drives() {
        return drives;
    }

    private void drivesChanged() {
        if (level != null && !level.isClientSide()) {
            for (int slot = 0; slot < SLOTS; slot++) {
                ItemStack stack = drives.getItem(slot);
                if (stack.getItem() instanceof HardDriveItem && stack.get(QboticsContent.MEDIA_ID.get()) == null) {
                    stack.set(QboticsContent.MEDIA_ID.get(), UUID.randomUUID());
                }
            }
        }
        setChanged();
    }

    /** Its drives by folder name under /net/disks: the name given in an anvil, or "disk" and the socket's number. */
    public Map<String, UUID> mounted() {
        Map<String, UUID> found = new LinkedHashMap<>();
        for (int slot = 0; slot < SLOTS; slot++) {
            ItemStack stack = drives.getItem(slot);
            UUID media = stack.get(QboticsContent.MEDIA_ID.get());
            if (media == null) {
                continue;
            }
            Component custom = stack.get(DataComponents.CUSTOM_NAME);
            String name = custom == null ? "" : Addresses.cleanName(custom.getString());
            if (name.isEmpty()) {
                name = "disk" + (slot + 1);
            }
            if (found.containsKey(name)) {
                name = name + "_" + (slot + 1);
            }
            found.put(name, media);
        }
        return found;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null && !moving) {
            Containers.dropContents(level, pos, drives);
            Containers.dropContents(level, pos, upgrades);
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                qbotics.upgrade.ChunkLoaders.release(serverLevel, pos);
            }
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.qbotics.server");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ServerMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("drives", ItemStack.OPTIONAL_CODEC.listOf(), List.of(drives.getItem(0), drives.getItem(1), drives.getItem(2), drives.getItem(3)));
        output.store("upgrade", ItemStack.OPTIONAL_CODEC, upgrades.getItem(0));
        output.putBoolean("chunk_loading", chunkLoading);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        List<ItemStack> held = input.read("drives", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int slot = 0; slot < SLOTS; slot++) {
            drives.setItem(slot, slot < held.size() ? held.get(slot) : ItemStack.EMPTY);
        }
        upgrades.setItem(0, input.read("upgrade", ItemStack.OPTIONAL_CODEC).orElse(ItemStack.EMPTY));
        chunkLoading = input.getBooleanOr("chunk_loading", true);
    }
}
