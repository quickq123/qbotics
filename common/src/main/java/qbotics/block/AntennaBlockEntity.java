package qbotics.block;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.Addresses;
import qbotics.network.RadioNetwork;

/**
 * An antenna: its cable network's router (design doc §9.3). It has an external address on
 * the server-wide radio network and is tuned to a frequency; only antennas on the same
 * frequency and in range can exchange messages.
 */
public class AntennaBlockEntity extends DeviceBlockEntity implements qbotics.limits.Limited, qbotics.limits.Limits.Ownable,
        net.minecraft.world.MenuProvider {
    /** How many upgrades an antenna takes. */
    public static final int UPGRADE_SLOTS = 2;

    /** Its upgrades (a Transport Upgrade, a Chunk Loader Upgrade): one of each kind at most. */
    private final net.minecraft.world.SimpleContainer upgrades = new net.minecraft.world.SimpleContainer(UPGRADE_SLOTS) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, net.minecraft.world.item.ItemStack stack) {
            qbotics.upgrade.UpgradeItem.Kind kind = qbotics.upgrade.UpgradeItem.kindOf(stack);
            if (kind != qbotics.upgrade.UpgradeItem.Kind.TRANSPORT && kind != qbotics.upgrade.UpgradeItem.Kind.CHUNK_LOADER) {
                return false; // not an upgrade, or one only a Qbot takes (Sense)
            }
            for (int other = 0; other < getContainerSize(); other++) {
                if (other != slot && qbotics.upgrade.UpgradeItem.kindOf(getItem(other)) == kind) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            AntennaBlockEntity.this.setChanged();
            qbotics.upgrade.ChunkLoaders.changed();
        }
    };
    /** Whether its Chunk Loader Upgrade is switched on (it is, until switched off). */
    private boolean chunkLoading = true;
    /** What its chunk loading is doing now, and how many chunks: worked out by ChunkLoaders, not saved. */
    private int loaderStatus;
    private int loadedChunks;
    private int wantedChunks;

    /** Inert: over its owner's limit, or placed by a machine (design doc §5.3). It routes nothing. */
    private boolean bricked;

    @Override
    public String limitType() {
        return qbotics.limits.Limits.ANTENNA;
    }

    @Override
    public boolean bricked() {
        return bricked;
    }

    @Override
    public void setBricked(boolean value) {
        if (bricked != value) {
            bricked = value;
            setChanged();
            qbotics.network.CableNetwork.changed(getBlockPos());
        }
    }


    /** Its number on the radio network; 0 until the server has given it one. */
    private int externalNumber;
    private String frequency = RadioNetwork.DEFAULT_FREQUENCY;
    /** Who may reach this antenna's network from outside. */
    private RadioNetwork.Access access = RadioNetwork.Access.PRIVATE;

    public AntennaBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.ANTENNA_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.ANTENNA;
    }

    public net.minecraft.world.SimpleContainer upgrades() {
        return upgrades;
    }

    /** True if it has an upgrade of this kind fitted. */
    public boolean hasUpgrade(qbotics.upgrade.UpgradeItem.Kind kind) {
        for (int slot = 0; slot < upgrades.getContainerSize(); slot++) {
            if (qbotics.upgrade.UpgradeItem.kindOf(upgrades.getItem(slot)) == kind) {
                return true;
            }
        }
        return false;
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

    /** Set by ChunkLoaders: what its loading is doing (a ChunkLoaders.Status), and how many of the chunks it wants it holds. */
    public void setLoaderState(int status, int loaded, int wanted) {
        loaderStatus = status;
        loadedChunks = loaded;
        wantedChunks = wanted;
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(int id, net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.world.entity.player.Player player) {
        return new AntennaMenu(id, inventory, this);
    }

    public RadioNetwork.Tier tier() {
        return getBlockState().getBlock() instanceof AntennaBlock antenna ? antenna.tier() : RadioNetwork.Tier.SHORT;
    }

    public String externalIp() {
        return RadioNetwork.externalIp(externalNumber);
    }

    public String frequency() {
        return frequency;
    }

    public RadioNetwork.Access access() {
        return access;
    }

    public void setAccess(RadioNetwork.Access wanted) {
        access = wanted;
        setChanged();
    }

    /** Frequencies follow the same rules as names; an unusable one leaves the antenna as it was. */
    public boolean tune(String wanted) {
        String cleaned = Addresses.cleanName(wanted);
        if (cleaned.isEmpty()) {
            return false;
        }
        frequency = cleaned;
        setChanged();
        return true;
    }

    // An antenna is on the radio network for exactly as long as its block is loaded.

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level instanceof ServerLevel serverLevel) {
            qbotics.limits.Limits.loaded(serverLevel, this);
            int number = RadioNetwork.get(serverLevel.getServer()).register(this, externalNumber);
            if (number != externalNumber) {
                externalNumber = number;
                setChanged();
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level instanceof ServerLevel serverLevel) {
            RadioNetwork.get(serverLevel.getServer()).unregister(this, externalNumber);
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level instanceof ServerLevel serverLevel) {
            externalNumber = RadioNetwork.get(serverLevel.getServer()).register(this, externalNumber);
        }
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (level instanceof ServerLevel serverLevel) {
            qbotics.limits.Limits.removed(serverLevel, this);
        }
        // Broken, not merely unloaded: the address goes back to the pool, its chunks are let go, its upgrades drop.
        if (level instanceof ServerLevel serverLevel) {
            qbotics.upgrade.ChunkLoaders.release(serverLevel, pos);
            net.minecraft.world.Containers.dropContents(serverLevel, pos, upgrades);
            RadioNetwork.get(serverLevel.getServer()).release(this, externalNumber);
            externalNumber = 0;
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("bricked", bricked);
        output.putInt("external", externalNumber);
        output.putString("frequency", frequency);
        output.putString("access", access.word);
        output.store("upgrades", net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.listOf(), upgrades.getItems());
        output.putBoolean("chunk_loading", chunkLoading);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        bricked = input.getBooleanOr("bricked", false);
        externalNumber = input.getIntOr("external", 0);
        String saved = Addresses.cleanName(input.getStringOr("frequency", ""));
        frequency = saved.isEmpty() ? RadioNetwork.DEFAULT_FREQUENCY : saved;
        RadioNetwork.Access savedAccess = RadioNetwork.Access.parse(input.getStringOr("access", ""));
        access = savedAccess == null ? RadioNetwork.Access.PRIVATE : savedAccess;
        java.util.List<net.minecraft.world.item.ItemStack> saved2 = input.read("upgrades",
                net.minecraft.world.item.ItemStack.OPTIONAL_CODEC.listOf()).orElse(java.util.List.of());
        for (int slot = 0; slot < UPGRADE_SLOTS; slot++) {
            upgrades.setItem(slot, slot < saved2.size() ? saved2.get(slot) : net.minecraft.world.item.ItemStack.EMPTY);
        }
        chunkLoading = input.getBooleanOr("chunk_loading", true);
    }
}
