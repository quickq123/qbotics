package qbotics.neoforge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import qbotics.network.ItemPort;

/** A container reached through NeoForge's item capability, which most mods' machines provide. */
record HandlerItemPort(ResourceHandler<ItemResource> handler) implements ItemPort {
    @Override
    public List<ItemStack> contents() {
        int size = handler.size();
        List<ItemStack> stacks = new ArrayList<>(size);
        for (int slot = 0; slot < size; slot++) {
            ItemResource resource = handler.getResource(slot);
            int amount = handler.getAmountAsInt(slot);
            stacks.add(resource.isEmpty() || amount <= 0 ? ItemStack.EMPTY : resource.toStack(amount));
        }
        return stacks;
    }

    @Override
    public int insert(ItemStack like, int max, boolean simulate) {
        if (like.isEmpty() || max <= 0) {
            return 0;
        }
        // A transaction that is closed without being committed changes nothing: that is the trial run.
        try (Transaction transaction = Transaction.openRoot()) {
            int inserted = handler.insert(ItemResource.of(like), max, transaction);
            if (!simulate) {
                transaction.commit();
            }
            return inserted;
        }
    }

    @Override
    public int insertInto(int slot, ItemStack like, int max, boolean simulate) {
        if (like.isEmpty() || max <= 0 || slot >= handler.size()) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int inserted = handler.insert(slot, ItemResource.of(like), max, transaction);
            if (!simulate) {
                transaction.commit();
            }
            return inserted;
        }
    }

    @Override
    public int extractFrom(int slot, ItemStack like, int max, boolean simulate) {
        if (like.isEmpty() || max <= 0 || slot >= handler.size()) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = handler.extract(slot, ItemResource.of(like), max, transaction);
            if (!simulate) {
                transaction.commit();
            }
            return extracted;
        }
    }

    @Override
    public int extract(ItemStack like, int max, boolean simulate) {
        if (like.isEmpty() || max <= 0) {
            return 0;
        }
        try (Transaction transaction = Transaction.openRoot()) {
            int extracted = handler.extract(ItemResource.of(like), max, transaction);
            if (!simulate) {
                transaction.commit();
            }
            return extracted;
        }
    }
}
