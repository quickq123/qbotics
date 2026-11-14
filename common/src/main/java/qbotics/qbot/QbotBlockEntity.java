package qbotics.qbot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.block.ComputerBlockEntity;
import qbotics.network.ContainerItemPort;
import qbotics.network.EnergyPort;
import qbotics.network.FluidPort;
import qbotics.network.ItemPort;
import qbotics.upgrade.UpgradeItem;

/**
 * A Qbot (design doc §8): a computer that moves. It runs Python like any computer, and carries
 * 16 slots of items (a 4x4 grid), a tool in its hand, an upgrade, a 1,000,000 FE energy cell
 * and two 4,000 mB tanks, so it can hold water and lava at once. There is no fuel: the cell
 * and tanks are things it carries, not what it runs on.
 */
public class QbotBlockEntity extends ComputerBlockEntity implements MenuProvider {
    public static final int SLOTS = 16;
    public static final int ENERGY_CAPACITY = 1_000_000;
    public static final int TANKS = 2;
    public static final int TANK_CAPACITY = 4 * FluidPort.BUCKET;

    /** The Qbots loaded now, for chunk loading. Server only. */
    private static final Set<QbotBlockEntity> LOADED = java.util.Collections.newSetFromMap(new WeakHashMap<>());

    private final SimpleContainer items = new SimpleContainer(SLOTS) {
        @Override
        public void setChanged() {
            super.setChanged();
            QbotBlockEntity.this.setChanged();
        }
    };
    /** What it holds in its hand: a tool it digs, attacks and uses with. */
    private final SimpleContainer hand = new SimpleContainer(1) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            QbotBlockEntity.this.handChanged();
        }
    };
    /** Its upgrade: a Chunk Loader Upgrade, for now the only kind a Qbot takes. */
    private final SimpleContainer upgrades = new SimpleContainer(1) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean canPlaceItem(int slot, ItemStack stack) {
            return UpgradeItem.kindOf(stack) == UpgradeItem.Kind.CHUNK_LOADER;
        }

        @Override
        public void setChanged() {
            super.setChanged();
            QbotBlockEntity.this.setChanged();
            qbotics.upgrade.ChunkLoaders.changed();
        }
    };
    private int energy;
    private final Fluid[] fluids = {Fluids.EMPTY, Fluids.EMPTY};
    private final int[] amounts = new int[TANKS];

    // What its renderer animates, sent to players' games. Times are game ticks.
    /** The way it came from on its last step (null if it hasn't stepped), and when. */
    @Nullable Direction stepFrom;
    long stepAt;
    /** The way it faced before its last turn, and when it turned. */
    @Nullable Direction turnFrom;
    long turnAt;
    /** When it last swung its arm (dug, placed, attacked, used), and towards which side. */
    long swingAt = -100;
    Direction swingSide = Direction.NORTH;

    // The block it is breaking, and since when (breaking takes as long as a player's would).
    @Nullable BlockPos breakingPos;
    @Nullable BlockState breakingState;
    long breakingSince;

    public QbotBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.QBOT_BLOCK_ENTITY.get(), pos, state);
    }

    public QbotTier tier() {
        return getBlockState().getBlock() instanceof QbotBlock block ? block.tier() : QbotTier.IRON;
    }

    @Override
    public String limitType() {
        return qbotics.limits.Limits.QBOT;
    }

    public Direction facing() {
        return getBlockState().getValue(QbotBlock.FACING);
    }

    // ---- its radio: the laptop it is built from, an Infinite antenna (design doc §8) ----

    /** The port a Qbot's radio answers on: it is its own router and its own computer. */
    public static final int RADIO_PORT = qbotics.network.RadioNetwork.PORT_BASE + 1;
    /** Its external address number (100.64.x.y), given the first time it is needed and kept as it moves. */
    private int radioNumber;

    /** Its external address, without the port ("100.64.0.9"), given now if it has none. */
    public String radioIp(net.minecraft.server.MinecraftServer server) {
        if (radioNumber <= 0) {
            radioNumber = qbotics.network.RadioNetwork.get(server).takeAddress();
            setChanged();
        }
        return qbotics.network.RadioNetwork.externalIp(radioNumber);
    }

    /** Its external address with its port, as network.send() takes it ("100.64.0.9:1001"). */
    public String radioAddress(net.minecraft.server.MinecraftServer server) {
        return radioIp(server) + ":" + RADIO_PORT;
    }

    /** Its external address if it has one yet, else "". */
    public String radioIpIfAny() {
        return qbotics.network.RadioNetwork.externalIp(radioNumber);
    }

    public SimpleContainer items() {
        return items;
    }

    public SimpleContainer hand() {
        return hand;
    }

    public SimpleContainer upgrades() {
        return upgrades;
    }

    public ItemStack tool() {
        return hand.getItem(0);
    }

    public boolean hasUpgrade(UpgradeItem.Kind kind) {
        return UpgradeItem.kindOf(upgrades.getItem(0)) == kind;
    }

    public ItemPort itemPort() {
        return new ContainerItemPort(items);
    }

    private void handChanged() {
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    // ---- the energy cell ---------------------------------------------------------------------

    public int energy() {
        return energy;
    }

    public void setEnergy(int value) {
        energy = Math.clamp(value, 0, ENERGY_CAPACITY);
        setChanged();
    }

    public EnergyPort energyPort() {
        return new EnergyPort() {
            @Override
            public long stored() {
                return energy;
            }

            @Override
            public long capacity() {
                return ENERGY_CAPACITY;
            }

            @Override
            public int insert(int max, boolean simulate) {
                int moved = Math.clamp(max, 0, ENERGY_CAPACITY - energy);
                if (!simulate && moved > 0) {
                    setEnergy(energy + moved);
                }
                return moved;
            }

            @Override
            public int extract(int max, boolean simulate) {
                int moved = Math.clamp(max, 0, energy);
                if (!simulate && moved > 0) {
                    setEnergy(energy - moved);
                }
                return moved;
            }
        };
    }

    // ---- the two tanks -----------------------------------------------------------------------

    public FluidPort fluidPort() {
        return new FluidPort() {
            @Override
            public List<Tank> tanks() {
                List<Tank> list = new ArrayList<>(TANKS);
                for (int i = 0; i < TANKS; i++) {
                    list.add(new Tank(amounts[i] > 0 ? fluids[i] : null, amounts[i], TANK_CAPACITY));
                }
                return list;
            }

            @Override
            public int insert(Fluid fluid, int max, boolean simulate) {
                // The tank that already holds it first; then an empty one.
                int left = max;
                for (int pass = 0; pass < 2 && left > 0; pass++) {
                    for (int i = 0; i < TANKS && left > 0; i++) {
                        boolean wanted = pass == 0 ? amounts[i] > 0 && fluids[i] == fluid : amounts[i] == 0;
                        if (wanted) {
                            left -= insertInto(i, fluid, left, simulate);
                        }
                    }
                }
                return max - left;
            }

            @Override
            public int extract(Fluid fluid, int max, boolean simulate) {
                int left = max;
                for (int i = 0; i < TANKS && left > 0; i++) {
                    left -= extractFrom(i, fluid, left, simulate);
                }
                return max - left;
            }

            @Override
            public int insertInto(int tank, Fluid fluid, int max, boolean simulate) {
                if (tank < 0 || tank >= TANKS || fluid == Fluids.EMPTY || (amounts[tank] > 0 && fluids[tank] != fluid)) {
                    return 0;
                }
                int moved = Math.clamp(max, 0, TANK_CAPACITY - amounts[tank]);
                if (!simulate && moved > 0) {
                    fluids[tank] = fluid;
                    amounts[tank] += moved;
                    setChanged();
                }
                return moved;
            }

            @Override
            public int extractFrom(int tank, Fluid fluid, int max, boolean simulate) {
                if (tank < 0 || tank >= TANKS || amounts[tank] == 0 || fluids[tank] != fluid) {
                    return 0;
                }
                int moved = Math.clamp(max, 0, amounts[tank]);
                if (!simulate && moved > 0) {
                    amounts[tank] -= moved;
                    if (amounts[tank] == 0) {
                        fluids[tank] = Fluids.EMPTY;
                    }
                    setChanged();
                }
                return moved;
            }
        };
    }

    /** A tank's fluid (empty if none) and amount, for its screen. */
    public Fluid tankFluid(int tank) {
        return fluids[tank];
    }

    public int tankAmount(int tank) {
        return amounts[tank];
    }

    // ---- animation ---------------------------------------------------------------------------

    public @Nullable BlockPos breakingPos() {
        return breakingPos;
    }

    public @Nullable BlockState breakingState() {
        return breakingState;
    }

    public long breakingSince() {
        return breakingSince;
    }

    /** Starts (or, with nulls, ends) breaking a block. */
    public void startBreaking(@Nullable BlockPos pos, @Nullable BlockState state, long now) {
        breakingPos = pos == null ? null : pos.immutable();
        breakingState = state;
        breakingSince = now;
    }

    /** Its arm swings towards a side: shown to players nearby. */
    public void swingTowards(Direction side) {
        if (level != null) {
            swingAt = level.getGameTime();
            swingSide = side;
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
        }
    }

    public @Nullable Direction stepFrom() {
        return stepFrom;
    }

    public long stepAt() {
        return stepAt;
    }

    public @Nullable Direction turnFrom() {
        return turnFrom;
    }

    public long turnAt() {
        return turnAt;
    }

    public long swingAt() {
        return swingAt;
    }

    public Direction swingSide() {
        return swingSide;
    }

    // ---- loaded, placed, broken ---------------------------------------------------------------

    @Override
    public void setLevel(Level newLevel) {
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

    /** The Qbots loaded now (server). */
    public static List<QbotBlockEntity> loaded() {
        List<QbotBlockEntity> list = new ArrayList<>();
        for (QbotBlockEntity qbot : LOADED) {
            if (!qbot.isRemoved()) {
                list.add(qbot);
            }
        }
        return list;
    }

    /** Broken (not stepping): what it carries falls out; its energy and fluid stay in the item. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (!moving && level instanceof ServerLevel serverLevel) {
            Containers.dropContents(serverLevel, pos, items);
            Containers.dropContents(serverLevel, pos, hand);
            Containers.dropContents(serverLevel, pos, upgrades);
            qbotics.upgrade.ChunkLoaders.release(serverLevel, pos);
        }
    }

    @Override
    public Component getDisplayName() {
        return getBlockState().getBlock().getName();
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new QbotMenu(id, inventory, this);
    }

    // ---- saving ------------------------------------------------------------------------------

    private static final com.mojang.serialization.Codec<List<ItemStack>> STACKS = ItemStack.OPTIONAL_CODEC.listOf();

    /** True while its program runs: after a reload it is started again (from the top). */
    private boolean programRunning;

    public boolean programRunning() {
        return programRunning;
    }

    public void setProgramRunning(boolean running) {
        if (programRunning != running) {
            programRunning = running;
            setChanged();
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("program_running", programRunning);
        output.putInt("radio", radioNumber);
        output.store("items", STACKS, items.getItems());
        output.store("hand", STACKS, hand.getItems());
        output.store("upgrades", STACKS, upgrades.getItems());
        saveStores(output);
        saveAnimation(output);
    }

    private void saveStores(ValueOutput output) {
        output.putInt("energy", energy);
        for (int i = 0; i < TANKS; i++) {
            output.putString("fluid" + i, BuiltInRegistries.FLUID.getKey(fluids[i]).toString());
            output.putInt("amount" + i, amounts[i]);
        }
    }

    private void saveAnimation(ValueOutput output) {
        output.putInt("step_from", stepFrom == null ? -1 : stepFrom.get3DDataValue());
        output.putLong("step_at", stepAt);
        output.putInt("turn_from", turnFrom == null ? -1 : turnFrom.get3DDataValue());
        output.putLong("turn_at", turnAt);
        output.putLong("swing_at", swingAt);
        output.putInt("swing_side", swingSide.get3DDataValue());
    }

    private static void fill(SimpleContainer container, List<ItemStack> saved) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            container.setItem(slot, slot < saved.size() ? saved.get(slot) : ItemStack.EMPTY);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        programRunning = input.getBooleanOr("program_running", false);
        radioNumber = input.getIntOr("radio", 0);
        input.read("items", STACKS).ifPresent(saved -> fill(items, saved));
        input.read("hand", STACKS).ifPresent(saved -> fill(hand, saved));
        input.read("upgrades", STACKS).ifPresent(saved -> fill(upgrades, saved));
        loadStores(input);
        int from = input.getIntOr("step_from", -1);
        stepFrom = from < 0 ? null : Direction.from3DDataValue(from);
        stepAt = input.getLongOr("step_at", 0);
        int turned = input.getIntOr("turn_from", -1);
        turnFrom = turned < 0 ? null : Direction.from3DDataValue(turned);
        turnAt = input.getLongOr("turn_at", 0);
        swingAt = input.getLongOr("swing_at", -100);
        swingSide = Direction.from3DDataValue(input.getIntOr("swing_side", 2));
    }

    private void loadStores(ValueInput input) {
        energy = Math.clamp(input.getIntOr("energy", 0), 0, ENERGY_CAPACITY);
        for (int i = 0; i < TANKS; i++) {
            Identifier id = Identifier.tryParse(input.getStringOr("fluid" + i, "minecraft:empty"));
            Fluid fluid = id == null ? Fluids.EMPTY : BuiltInRegistries.FLUID.getValue(id);
            amounts[i] = Math.clamp(input.getIntOr("amount" + i, 0), 0, TANK_CAPACITY);
            fluids[i] = amounts[i] == 0 ? Fluids.EMPTY : fluid;
        }
    }

    // Players' games are told how to draw it: its animation and what it holds in its hand.
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, registries);
        output.store("hand", STACKS, hand.getItems());
        saveAnimation(output);
        return output.buildResult();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // Its energy and fluid stay with it when it is broken and picked up (its items fall out).

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (energy > 0 || amounts[0] > 0 || amounts[1] > 0) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("energy", energy);
            for (int i = 0; i < TANKS; i++) {
                tag.putString("fluid" + i, BuiltInRegistries.FLUID.getKey(fluids[i]).toString());
                tag.putInt("amount" + i, amounts[i]);
            }
            components.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        }
    }

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        CustomData data = components.get(DataComponents.CUSTOM_DATA);
        if (data != null) {
            CompoundTag tag = data.copyTag();
            energy = Math.clamp(tag.getIntOr("energy", 0), 0, ENERGY_CAPACITY);
            for (int i = 0; i < TANKS; i++) {
                Identifier id = Identifier.tryParse(tag.getStringOr("fluid" + i, "minecraft:empty"));
                amounts[i] = Math.clamp(tag.getIntOr("amount" + i, 0), 0, TANK_CAPACITY);
                fluids[i] = id == null || amounts[i] == 0 ? Fluids.EMPTY : BuiltInRegistries.FLUID.getValue(id);
            }
        }
    }
}
