package qbotics.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * A player's inventory as a Player Interface reaches it, numbered as the game numbers it:
 * 0-8 the hotbar, 9-35 the rest, 36 feet, 37 legs, 38 chest, 39 head, 40 the off hand.
 * <p>
 * Armour slots take only what can be worn there, one at a time, and keep what Curse of
 * Binding holds on, as the inventory screen does. A move that names no slot uses slots 0-35
 * only, so nothing is put on or taken off the player's body unless a program asks for that
 * slot by number.
 */
public final class PlayerItemPort implements ItemPort {
    /** Slots 0-40: the inventory, the armour and the off hand. */
    public static final int SLOTS = Inventory.SLOT_OFFHAND + 1;

    private final ServerPlayer player;

    public PlayerItemPort(ServerPlayer player) {
        this.player = player;
    }

    private Inventory inventory() {
        return player.getInventory();
    }

    /** The armour piece a slot holds, or null for a slot that isn't armour. */
    private static EquipmentSlot armour(int slot) {
        EquipmentSlot equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(slot);
        return equipment != null && equipment.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? equipment : null;
    }

    @Override
    public List<ItemStack> contents() {
        List<ItemStack> copy = new ArrayList<>(SLOTS);
        for (int slot = 0; slot < SLOTS; slot++) {
            copy.add(inventory().getItem(slot).copy());
        }
        return copy;
    }

    @Override
    public int insert(ItemStack like, int max, boolean simulate) {
        int left = max;
        // Top up stacks of the same item before starting an empty slot, as picking items up does.
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int slot = 0; slot < Inventory.INVENTORY_SIZE && left > 0; slot++) {
                ItemStack held = inventory().getItem(slot);
                boolean wanted = pass == 0 ? !held.isEmpty() && ItemStack.isSameItemSameComponents(held, like) : held.isEmpty();
                if (wanted) {
                    left -= insertInto(slot, like, left, simulate);
                }
            }
        }
        return max - left;
    }

    @Override
    public boolean mayPlace(int slot, ItemStack like) {
        if (slot < 0 || slot >= SLOTS) {
            return false;
        }
        EquipmentSlot worn = armour(slot);
        return worn == null || like.canEquip(worn, player);
    }

    @Override
    public int insertInto(int slot, ItemStack like, int max, boolean simulate) {
        if (slot < 0 || slot >= SLOTS) {
            return 0;
        }
        ItemStack held = inventory().getItem(slot);
        if (!held.isEmpty() && !ItemStack.isSameItemSameComponents(held, like)) {
            return 0;
        }
        EquipmentSlot worn = armour(slot);
        if (worn != null && !like.canEquip(worn, player)) {
            return 0;
        }
        int most = worn != null ? 1 : Math.min(inventory().getMaxStackSize(like), like.getMaxStackSize());
        int room = Math.clamp(most - held.getCount(), 0, Math.max(0, max));
        if (!simulate && room > 0) {
            inventory().setItem(slot, held.isEmpty() ? like.copyWithCount(room) : held.copyWithCount(held.getCount() + room));
            inventory().setChanged();
        }
        return room;
    }

    @Override
    public int extractFrom(int slot, ItemStack like, int max, boolean simulate) {
        if (slot < 0 || slot >= SLOTS) {
            return 0;
        }
        ItemStack held = inventory().getItem(slot);
        if (held.isEmpty() || !ItemStack.isSameItemSameComponents(held, like)) {
            return 0;
        }
        if (armour(slot) != null && !player.isCreative() && EnchantmentHelper.has(held, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            return 0; // Curse of Binding: it stays on
        }
        int take = Math.clamp(max, 0, held.getCount());
        if (!simulate && take > 0) {
            inventory().setItem(slot, take == held.getCount() ? ItemStack.EMPTY : held.copyWithCount(held.getCount() - take));
            inventory().setChanged();
        }
        return take;
    }

    @Override
    public int extract(ItemStack like, int max, boolean simulate) {
        int left = max;
        for (int slot = 0; slot < Inventory.INVENTORY_SIZE && left > 0; slot++) {
            left -= extractFrom(slot, like, left, simulate);
        }
        return max - left;
    }
}
