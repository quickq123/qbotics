package qbotics.qbot;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.upgrade.UpgradeItem;

/**
 * A Qbot's screen (right-click it): its 4x4 inventory, the tool in its hand, its upgrade, gauges
 * for its energy cell and two tanks, and the Run, Pause and Stop buttons for its program, with
 * the last lines that program printed. {@code qbotics.client.QbotScreen} draws it.
 */
public class QbotMenu extends AbstractContainerMenu {
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    public static final int HAND_X = 92;
    public static final int HAND_Y = 18;
    public static final int UPGRADE_Y = 54;
    public static final int GAUGES_X = 120;
    /** The row of buttons, and below it the program's state and output. */
    public static final int BUTTONS_Y = 94;
    public static final int OUTPUT_Y = 114;
    public static final int TOP_HEIGHT = 148;
    public static final int RUN_BUTTON = 0;
    public static final int PAUSE_BUTTON = 1;
    public static final int STOP_BUTTON = 2;
    /** Data: 0-5 energy and tanks, 6 the program's state, 7 how it last ended, 8-15 the Qbot's identity. */
    private static final int DATA = 16;
    /** Slots: 0-15 the grid, 16 the hand, 17 the upgrade; then the player's. */
    public static final int HAND_SLOT = QbotBlockEntity.SLOTS;
    public static final int UPGRADE_SLOT = HAND_SLOT + 1;
    private static final int OWN_SLOTS = UPGRADE_SLOT + 1;

    private final @Nullable QbotBlockEntity qbot;
    private final Container items;
    private final ContainerData data;

    /** On the server. */
    public QbotMenu(int containerId, Inventory inventory, QbotBlockEntity qbot) {
        this(containerId, inventory, qbot, qbot.items(), qbot.hand(), qbot.upgrades(), new ContainerData() {
            @Override
            public int get(int index) {
                return switch (index) {
                    case 0 -> qbot.energy() & 0xFFFF;
                    case 1 -> (qbot.energy() >>> 16) & 0xFFFF;
                    case 2, 3 -> qbot.tankAmount(index - 2) == 0 ? 0 : BuiltInRegistries.FLUID.getId(qbot.tankFluid(index - 2));
                    case 4, 5 -> qbot.tankAmount(index - 4);
                    case 6 -> program(qbot) == null ? 0 : program(qbot).programState().ordinal();
                    case 7 -> program(qbot) == null ? 0 : ended(program(qbot).programStatus());
                    default -> idPart(qbot.deviceId(), index - 8);
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return DATA;
            }
        });
        if (inventory.player instanceof net.minecraft.server.level.ServerPlayer player) {
            qbotics.computer.ComputerManager.get(player.level().getServer()).watchOutput(player, qbot);
        }
    }

    /** How a program last ended, as a number for the screen; see {@link #ending()}. */
    public enum Ending { NONE, FINISHED, STOPPED, ERROR, NO_PROGRAM, BUSY }

    private static int ended(String status) {
        Ending ending = status.isEmpty() ? Ending.NONE : status.equals("Finished") ? Ending.FINISHED : status.equals("Stopped") ? Ending.STOPPED
                : status.startsWith("Error") ? Ending.ERROR : status.startsWith("No program") ? Ending.NO_PROGRAM : Ending.BUSY;
        return ending.ordinal();
    }

    private static int idPart(java.util.UUID id, int part) {
        long bits = part < 4 ? id.getMostSignificantBits() : id.getLeastSignificantBits();
        return (int) (bits >>> (16 * (3 - part % 4))) & 0xFFFF;
    }

    /** The Qbot's running computer, or null if it isn't switched on. On the server. */
    private static qbotics.computer.@Nullable ServerComputer program(QbotBlockEntity qbot) {
        return qbot.getLevel() == null || qbot.getLevel().getServer() == null ? null
                : qbotics.computer.ComputerManager.get(qbot.getLevel().getServer()).computer(qbot.deviceId());
    }

    /** What its program is doing: stopped, running or paused. */
    public qbotics.computer.ServerComputer.ProgramState programState() {
        qbotics.computer.ServerComputer.ProgramState[] states = qbotics.computer.ServerComputer.ProgramState.values();
        return states[Math.clamp(data.get(6), 0, states.length - 1)];
    }

    public Ending ending() {
        return Ending.values()[Math.clamp(data.get(7), 0, Ending.values().length - 1)];
    }

    /** Which Qbot this is (its device identity), to find its terminal's lines in the player's game. */
    public java.util.UUID qbotId() {
        long most = 0;
        long least = 0;
        for (int part = 0; part < 4; part++) {
            most = (most << 16) | (data.get(8 + part) & 0xFFFF);
            least = (least << 16) | (data.get(12 + part) & 0xFFFF);
        }
        return new java.util.UUID(most, least);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (qbot == null || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return false;
        }
        qbotics.computer.ServerComputer computer = qbotics.computer.ComputerManager.get(serverPlayer.level().getServer()).getOrStart(qbot);
        switch (id) {
            case RUN_BUTTON -> computer.runProgram();
            case PAUSE_BUTTON -> computer.pauseProgram();
            case STOP_BUTTON -> computer.stopProgram();
            default -> {
                return false;
            }
        }
        return true;
    }

    /** In the player's game. */
    public QbotMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(QbotBlockEntity.SLOTS), new SimpleContainer(1), new SimpleContainer(1),
                new SimpleContainerData(DATA));
    }

    private QbotMenu(int containerId, Inventory inventory, @Nullable QbotBlockEntity qbot, Container items, Container hand,
            Container upgrades, ContainerData data) {
        super(QboticsContent.QBOT_MENU.get(), containerId);
        this.qbot = qbot;
        this.items = items;
        this.data = data;
        items.startOpen(inventory.player);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                addSlot(new Slot(items, row * 4 + col, GRID_X + col * 18, GRID_Y + row * 18));
            }
        }
        addSlot(new Slot(hand, 0, HAND_X, HAND_Y) {
            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        addSlot(new Slot(upgrades, 0, HAND_X, UPGRADE_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return UpgradeItem.kindOf(stack) == UpgradeItem.Kind.CHUNK_LOADER;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }
        });
        addStandardInventorySlots(inventory, 8, TOP_HEIGHT + 14);
        addDataSlots(data);
    }

    public int energy() {
        return (data.get(0) & 0xFFFF) | ((data.get(1) & 0xFFFF) << 16);
    }

    public Fluid fluid(int tank) {
        int id = data.get(2 + tank);
        return id == 0 ? Fluids.EMPTY : BuiltInRegistries.FLUID.byId(id);
    }

    public int amount(int tank) {
        return data.get(4 + tank);
    }

    @Override
    public boolean stillValid(Player player) {
        return items.stillValid(player) && (qbot == null || !qbot.isRemoved());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        if (slotIndex < OWN_SLOTS) {
            if (!moveItemStackTo(stack, OWN_SLOTS, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (UpgradeItem.kindOf(stack) == UpgradeItem.Kind.CHUNK_LOADER && !slots.get(UPGRADE_SLOT).hasItem()) {
            if (!moveItemStackTo(stack, UPGRADE_SLOT, UPGRADE_SLOT + 1, false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, QbotBlockEntity.SLOTS, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return clicked;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        items.stopOpen(player);
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            qbotics.computer.ComputerManager.get(serverPlayer.level().getServer()).closeTerminal(serverPlayer);
        }
    }
}
