package qbotics.block;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;

/**
 * The Programmer (design doc §5.4, §8.7): holds a hard drive of programs and copies one onto
 * the computer in front of its programming face, switching it on as its owner's. Redstone
 * picks the program (its level, 1 to 15, by programs.txt on the drive) and a rising signal
 * starts it; a computer placed in front of it is programmed as it arrives. It runs nothing
 * itself: its drive is edited in the editor, without a terminal.
 */
public class ProgrammerBlockEntity extends DeviceBlockEntity implements MenuProvider {
    private final SimpleContainer drive = new SimpleContainer(1) {
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
            ItemStack held = getItem(0);
            if (level != null && !level.isClientSide() && held.getItem() instanceof HardDriveItem
                    && held.get(QboticsContent.MEDIA_ID.get()) == null) {
                held.set(QboticsContent.MEDIA_ID.get(), UUID.randomUUID()); // a new drive gets its files' ID
            }
            ProgrammerBlockEntity.this.setChanged();
        }
    };
    /** The redstone level it last saw, so a rise can be told from a steady signal. */
    private int lastSignal;
    /** What happened the last time it tried, for its screen and programs. */
    private String status = "Waiting: no program sent yet.";

    public ProgrammerBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.PROGRAMMER_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.PROGRAMMER;
    }

    /** It doesn't join the network through its programming face, so its target stays on a network of its own. */
    @Override
    public boolean connectsOn(Direction side) {
        return side != facing();
    }

    public Direction facing() {
        return getBlockState().hasProperty(ProgrammerBlock.FACING) ? getBlockState().getValue(ProgrammerBlock.FACING) : Direction.NORTH;
    }

    public SimpleContainer driveSlot() {
        return drive;
    }

    /** The ID its hard drive's files are kept under, or null with no drive in it. */
    public @Nullable UUID mediaId() {
        return drive.getItem(0).get(QboticsContent.MEDIA_ID.get());
    }

    public int lastSignal() {
        return lastSignal;
    }

    void setLastSignal(int signal) {
        if (lastSignal != signal) {
            lastSignal = signal;
            setChanged();
        }
    }

    public String status() {
        return status;
    }

    void setStatus(String text) {
        status = text;
        setChanged();
    }

    /** Breaking it drops its drive, files and all. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level != null) {
            Containers.dropContents(level, pos, drive);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.qbotics.programmer");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new ProgrammerMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("drive", ItemStack.OPTIONAL_CODEC.listOf(), List.of(drive.getItem(0)));
        output.putInt("last_signal", lastSignal);
        output.putString("status", status);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        List<ItemStack> held = input.read("drive", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of());
        drive.setItem(0, held.isEmpty() ? ItemStack.EMPTY : held.getFirst());
        lastSignal = input.getIntOr("last_signal", 0);
        status = input.getStringOr("status", status);
    }
}
