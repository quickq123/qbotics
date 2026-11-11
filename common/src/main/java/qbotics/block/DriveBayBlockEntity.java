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
import qbotics.network.CableNetwork;

/**
 * The Drive Bay (design doc §17.6): four sockets for hard drives. Every computer on its cable
 * network sees each drive as a folder, {@code /net/<bay>/<drive>/}, so programs and players
 * move files between computers, and keep shared libraries, by copying them there.
 *
 * <p>A hard drive's files are kept on the server under its own ID and travel with the item:
 * take one out, put it in a bay elsewhere, and its files are there.
 */
public class DriveBayBlockEntity extends DeviceBlockEntity implements MenuProvider {
    public static final int SLOTS = 4;

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
            DriveBayBlockEntity.this.drivesChanged();
        }
    };

    public DriveBayBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.DRIVE_BAY_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.DRIVE_BAY;
    }

    public SimpleContainer drives() {
        return drives;
    }

    /** A drive went in or came out. A drive new from crafting is given its own files' ID now. */
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

    /** The folder name this bay has under /net: its name, or "bay" and its number on the network. */
    public String folderName() {
        return deviceName().isEmpty() ? "bay" + hostNumber() : deviceName();
    }

    /**
     * The drives in it, by the folder name each has under the bay: the name it was given in an
     * anvil, or "drive" and its socket's number. Each with the ID its files are kept under.
     */
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
                name = "drive" + (slot + 1);
            }
            if (found.containsKey(name)) {
                name = name + "_" + (slot + 1); // two drives of the same name: the second is told apart by its socket
            }
            found.put(name, media);
        }
        return found;
    }

    /** Breaking the bay drops its drives, files and all. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) {
            Containers.dropContents(level, pos, drives);
        }
        CableNetwork.changed();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.qbotics.drive_bay");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new DriveBayMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("drives", ItemStack.OPTIONAL_CODEC.listOf(), List.of(drives.getItem(0), drives.getItem(1), drives.getItem(2), drives.getItem(3)));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        List<ItemStack> held = input.read("drives", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        for (int slot = 0; slot < SLOTS; slot++) {
            drives.setItem(slot, slot < held.size() ? held.get(slot) : ItemStack.EMPTY);
        }
    }
}
