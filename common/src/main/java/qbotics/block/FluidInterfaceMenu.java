package qbotics.block;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jspecify.annotations.Nullable;
import qbotics.Qbotics;
import qbotics.QboticsContent;
import qbotics.network.FluidPort;

/**
 * The Fluid Interface's screen: for each of the four tanks, a filter socket above, the tank
 * itself, and a bucket slot below; then the player's inventory.
 * {@code qbotics.client.FluidInterfaceScreen} draws it.
 *
 * <p>A socket holds nothing. Clicking it with a bucket of a fluid (or any container holding
 * one) keeps that tank for that fluid; clicking with an empty hand lets it take any again. A
 * full container put in the bucket slot empties into the tank; an empty one fills from it.
 *
 * <p>The tanks' contents and filters reach the player's game as numbers (see {@link #data}).
 */
public class FluidInterfaceMenu extends AbstractContainerMenu {
    public static final int TANKS = FluidInterfaceBlockEntity.TANKS;
    /** Where things are on the screen, measured from its top-left corner. */
    public static final int SOCKET_Y = 18;
    public static final int TANK_Y = 38;
    public static final int TANK_HEIGHT = 52;
    public static final int BUCKET_Y = 94;
    /** The height of the part above the player's inventory. */
    public static final int TOP_HEIGHT = 114;

    /** The left edge of a tank's column (its socket, gauge and bucket slot). */
    public static int columnX(int tank) {
        return 26 + tank * 36;
    }

    /** The real block on the server; null in the player's game. */
    private final @Nullable FluidInterfaceBlockEntity store;
    private final Container buckets;
    /** A bucket of each tank's filter fluid, so the socket has something to show. */
    private final SimpleContainer shown = new SimpleContainer(TANKS);
    /**
     * For each tank: its fluid (as the game's number for it), how much it holds, and its
     * filter fluid. Kept up to date on the server and copied to the player's game.
     */
    private final ContainerData data;

    /** On the server. */
    public FluidInterfaceMenu(int containerId, Inventory inventory, FluidInterfaceBlockEntity store) {
        this(containerId, inventory, store, store.buckets(), new ContainerData() {
            @Override
            public int get(int index) {
                int tank = index % TANKS;
                FluidPort.Tank held = store.tanks().get(tank);
                return switch (index / TANKS) {
                    case 0 -> held.amount() == 0 ? 0 : BuiltInRegistries.FLUID.getId(held.fluid());
                    case 1 -> held.amount();
                    default -> store.filter(tank) == Fluids.EMPTY ? 0 : BuiltInRegistries.FLUID.getId(store.filter(tank));
                };
            }

            @Override
            public void set(int index, int value) {
            }

            @Override
            public int getCount() {
                return 3 * TANKS;
            }
        });
    }

    /** In the player's game. */
    public FluidInterfaceMenu(int containerId, Inventory inventory) {
        this(containerId, inventory, null, new SimpleContainer(TANKS), new SimpleContainerData(3 * TANKS));
    }

    private FluidInterfaceMenu(int containerId, Inventory inventory, @Nullable FluidInterfaceBlockEntity store, Container buckets,
            ContainerData data) {
        super(QboticsContent.FLUID_INTERFACE_MENU.get(), containerId);
        this.store = store;
        this.buckets = buckets;
        this.data = data;
        buckets.startOpen(inventory.player);
        showFilters();
        for (int tank = 0; tank < TANKS; tank++) {
            addSlot(new Slot(shown, tank, columnX(tank) + 1, SOCKET_Y) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return false;
                }

                @Override
                public boolean mayPickup(Player player) {
                    return false;
                }
            });
        }
        for (int tank = 0; tank < TANKS; tank++) {
            addSlot(new Slot(buckets, tank, columnX(tank) + 1, BUCKET_Y) {
                @Override
                public int getMaxStackSize() {
                    return 1;
                }
            });
        }
        addStandardInventorySlots(inventory, 8, TOP_HEIGHT + 14);
        addDataSlots(data);
    }

    /** True for the sockets, which the screen draws dark and which hold nothing. */
    public static boolean isFilterSlot(int slotIndex) {
        return slotIndex >= 0 && slotIndex < TANKS;
    }

    /** What a tank holds, in the player's game: the fluid (EMPTY if none) and how much. */
    public Fluid fluid(int tank) {
        return fluidById(data.get(tank));
    }

    public int amount(int tank) {
        return data.get(TANKS + tank);
    }

    /** The fluid a tank is kept for, or EMPTY if it takes any. */
    public Fluid filter(int tank) {
        return fluidById(data.get(2 * TANKS + tank));
    }

    private static Fluid fluidById(int id) {
        Fluid fluid = id <= 0 ? null : BuiltInRegistries.FLUID.byId(id);
        return fluid == null ? Fluids.EMPTY : fluid;
    }

    private void showFilters() {
        if (store == null) {
            return;
        }
        for (int tank = 0; tank < TANKS; tank++) {
            Fluid filter = store.filter(tank);
            ItemStack bucket = filter == Fluids.EMPTY ? ItemStack.EMPTY : new ItemStack(filter.getBucket());
            if (!ItemStack.isSameItem(shown.getItem(tank), bucket)) {
                shown.setItem(tank, bucket);
            }
        }
    }

    @Override
    public void clicked(int slotIndex, int button, ContainerInput kind, Player player) {
        if (isFilterSlot(slotIndex)) {
            // A socket takes nothing: it reads the fluid in what is held, or clears with an empty hand.
            if (store != null) {
                ItemStack held = getCarried();
                if (held.isEmpty()) {
                    store.setFilter(slotIndex, null);
                } else {
                    Fluid fluid = Qbotics.platform().fluidIn(held);
                    if (fluid != null) {
                        store.setFilter(slotIndex, fluid);
                    }
                }
                showFilters();
            }
            return;
        }
        super.clicked(slotIndex, button, kind, player);
    }

    @Override
    public void broadcastChanges() {
        showFilters(); // a program may have changed a filter while the screen is open
        super.broadcastChanges();
    }

    @Override
    public boolean stillValid(Player player) {
        return buckets.stillValid(player) && (store == null || !store.isRemoved());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = slots.get(slotIndex);
        if (isFilterSlot(slotIndex) || slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        int bucketsEnd = 2 * TANKS;
        if (slotIndex < bucketsEnd) {
            if (!moveItemStackTo(stack, bucketsEnd, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, TANKS, bucketsEnd, false)) {
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
        buckets.stopOpen(player);
    }
}
