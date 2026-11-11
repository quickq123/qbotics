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
 * A Qbot's screen (sneak and right-click it): its 4x4 inventory, the tool in its hand, its
 * upgrade, and gauges for its energy cell and two tanks. {@code qbotics.client.QbotScreen} draws it.
 */
public class QbotMenu extends AbstractContainerMenu {
    public static final int GRID_X = 8;
    public static final int GRID_Y = 18;
    public static final int HAND_X = 92;
    public static final int HAND_Y = 18;
    public static final int UPGRADE_Y = 54;
    public static final int GAUGES_X = 120;
    public static final int TOP_HEIGHT = 96;
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
                    default -> qbot.tankAmount(index - 4);
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 6;
            }
        });
    }

    /** In the player's game. */
    public QbotMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(QbotBlockEntity.SLOTS), new SimpleContainer(1), new SimpleContainer(1),
                new SimpleContainerData(6));
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
    }
}
