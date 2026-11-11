package qbotics.block;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.network.ItemPort;

/**
 * The Input block: the network's chest (design doc §11.0). Every slot starts closed; a
 * slot accepts only the item its filter names, and holds up to one stack of it. Hoppers
 * and other mods' pipes can put items in (where a filter allows) and take them out.
 */
public class InterfaceBlockEntity extends DeviceBlockEntity implements Container, MenuProvider, ItemPort {
    /** Basic tier (design doc §11.0, tiered upgrades). */
    public static final int SLOTS = 9;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    /** What each slot accepts; null means the slot is closed. */
    private final @Nullable Item[] filters = new Item[SLOTS];

    public InterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.INTERFACE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return Kind.INTERFACE;
    }

    public @Nullable Item filter(int slot) {
        return filters[slot];
    }

    /** Sets what a slot accepts; null closes it. Items already in the slot stay and can be taken out. */
    public void setFilter(int slot, @Nullable Item item) {
        filters[slot] = item;
        setChanged();
    }

    // ---- Container: what hoppers, players and other mods see --------------------------

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    @Override
    public boolean isEmpty() {
        return items.stream().allMatch(ItemStack::isEmpty);
    }

    @Override
    public ItemStack getItem(int slot) {
        return items.get(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int count) {
        ItemStack removed = ContainerHelper.removeItem(items, slot, count);
        if (!removed.isEmpty()) {
            setChanged();
        }
        return removed;
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return ContainerHelper.takeItem(items, slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        items.set(slot, stack);
        stack.limitSize(getMaxStackSize(stack));
        setChanged();
    }

    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return filters[slot] != null && stack.is(filters[slot]);
    }

    @Override
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    // ---- ItemPort: what the network's transfers use -----------------------------------

    @Override
    public List<ItemStack> contents() {
        List<ItemStack> copy = new ArrayList<>(SLOTS);
        for (ItemStack stack : items) {
            copy.add(stack.copy());
        }
        return copy;
    }

    @Override
    public int insert(ItemStack like, int max, boolean simulate) {
        int left = max;
        // Top up slots that already hold it before starting an empty one.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int slot = 0; slot < SLOTS && left > 0; slot++) {
                ItemStack held = items.get(slot);
                boolean wanted = pass == 0 ? !held.isEmpty() && ItemStack.isSameItemSameComponents(held, like) : held.isEmpty();
                if (!wanted || !canPlaceItem(slot, like)) {
                    continue;
                }
                int room = Math.min(left, getMaxStackSize(like) - held.getCount());
                if (room <= 0) {
                    continue;
                }
                if (!simulate) {
                    if (held.isEmpty()) {
                        items.set(slot, like.copyWithCount(room));
                    } else {
                        held.grow(room);
                    }
                }
                left -= room;
            }
        }
        if (!simulate && left < max) {
            setChanged();
        }
        return max - left;
    }

    @Override
    public int insertInto(int slot, ItemStack like, int max, boolean simulate) {
        ItemStack held = items.get(slot);
        if (!canPlaceItem(slot, like) || (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, like))) {
            return 0;
        }
        int room = Math.clamp(getMaxStackSize(like) - held.getCount(), 0, Math.max(0, max));
        if (!simulate && room > 0) {
            if (held.isEmpty()) {
                items.set(slot, like.copyWithCount(room));
            } else {
                held.grow(room);
            }
            setChanged();
        }
        return room;
    }

    @Override
    public int extractFrom(int slot, ItemStack like, int max, boolean simulate) {
        ItemStack held = items.get(slot);
        if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, like)) {
            return 0;
        }
        int take = Math.clamp(max, 0, held.getCount());
        if (!simulate && take > 0) {
            held.shrink(take);
            if (held.isEmpty()) {
                items.set(slot, ItemStack.EMPTY);
            }
            setChanged();
        }
        return take;
    }

    @Override
    public int extract(ItemStack like, int max, boolean simulate) {
        int left = max;
        for (int slot = 0; slot < SLOTS && left > 0; slot++) {
            ItemStack held = items.get(slot);
            if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, like)) {
                continue;
            }
            int take = Math.min(left, held.getCount());
            if (!simulate) {
                held.shrink(take);
                if (held.isEmpty()) {
                    items.set(slot, ItemStack.EMPTY);
                }
            }
            left -= take;
        }
        if (!simulate && left < max) {
            setChanged();
        }
        return max - left;
    }

    // ---- the screen a player sees -----------------------------------------------------

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.qbotics.interface", deviceName().isEmpty() ? ip() : deviceName());
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new InterfaceMenu(containerId, inventory, this);
    }

    // ---- saving ------------------------------------------------------------------------

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
        List<String> names = new ArrayList<>(SLOTS);
        for (Item filter : filters) {
            names.add(filter == null ? "" : BuiltInRegistries.ITEM.getKey(filter).toString());
        }
        output.store("filters", Codec.STRING.listOf(), names);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
        List<String> names = input.read("filters", Codec.STRING.listOf()).orElse(List.of());
        for (int slot = 0; slot < SLOTS; slot++) {
            Identifier id = slot < names.size() && !names.get(slot).isEmpty() ? Identifier.tryParse(names.get(slot)) : null;
            filters[slot] = id != null && BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.getValue(id) : null;
        }
    }
}
