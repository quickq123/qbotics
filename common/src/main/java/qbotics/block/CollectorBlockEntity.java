package qbotics.block;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import qbotics.QboticsContent;
import qbotics.network.CableNetwork;
import qbotics.network.ContainerItemPort;
import qbotics.network.NetworkDevice;

/**
 * A Collector (docs/reference.md §6.13): a chest's worth of slots that fills itself with what
 * lies in the box around it (see {@link AreaBlockEntity}). Twice a second it picks up dropped
 * items it has room for, once their pickup delay is over (as a player would), and experience
 * orbs, into an Experience Interface on its network if there is one; with none, orbs stay
 * where they are. Programs, hoppers and pipes take from it as from a chest.
 */
public class CollectorBlockEntity extends AreaBlockEntity implements Container, MenuProvider {
    public static final int SLOTS = 27;
    /** How often it looks, in ticks. */
    private static final int EVERY = 10;
    /** The most dropped items it looks at in one go. */
    private static final int MOST_AT_ONCE = 128;

    private final NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);

    public CollectorBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.COLLECTOR_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public NetworkDevice.Kind kind() {
        return NetworkDevice.Kind.COLLECTOR;
    }

    /** Called every tick on the server. */
    public void serverTick() {
        if (!(level instanceof ServerLevel server) || (server.getGameTime() + getBlockPos().asLong()) % EVERY != 0) {
            return;
        }
        ContainerItemPort into = new ContainerItemPort(this);
        int looked = 0;
        // Full to the last slot, it can take nothing: the items lying about aren't looked for (over a
        // mob farm that was up to 128 of them tried against every slot, twice a second, for nothing).
        boolean room = false;
        for (int slot = 0; slot < getContainerSize() && !room; slot++) {
            ItemStack held = getItem(slot);
            room = held.isEmpty() || held.getCount() < Math.min(held.getMaxStackSize(), getMaxStackSize());
        }
        for (ItemEntity dropped : !room ? List.<ItemEntity>of()
                : server.getEntitiesOfClass(ItemEntity.class, area(), entity -> entity.isAlive() && !entity.hasPickUpDelay() && holds(entity))) {
            if (looked++ >= MOST_AT_ONCE) {
                break;
            }
            if (!qbotics.network.Boundaries.mayReach(server, this, dropped.blockPosition())) {
                continue; // lying on land its owner may not change (another player's claim)
            }
            ItemStack stack = dropped.getItem();
            int put = into.insert(stack, stack.getCount(), false);
            if (put >= stack.getCount()) {
                dropped.discard();
            } else if (put > 0) {
                dropped.setItem(stack.copyWithCount(stack.getCount() - put));
            }
        }
        List<ExperienceInterfaceBlockEntity> banks = banks(server);
        if (banks.isEmpty()) {
            return; // nowhere to keep experience: the orbs stay
        }
        for (ExperienceOrb orb : server.getEntitiesOfClass(ExperienceOrb.class, area(), orb -> orb.isAlive() && holds(orb))) {
            if (!qbotics.network.Boundaries.mayReach(server, this, orb.blockPosition())) {
                continue;
            }
            long worth = Experience.orbPoints(orb);
            long left = worth;
            for (ExperienceInterfaceBlockEntity bank : banks) {
                left -= bank.add(left);
                if (left <= 0) {
                    break;
                }
            }
            if (left <= 0) {
                orb.discard();
            } else if (left < worth) {
                orb.discard(); // part went in; the rest goes back out as a new orb
                ExperienceOrb.award(server, orb.position(), (int) left);
                return;
            } else {
                return; // the stores are full
            }
        }
    }

    /** The Experience Interfaces on its cable network. */
    private List<ExperienceInterfaceBlockEntity> banks(ServerLevel server) {
        List<ExperienceInterfaceBlockEntity> found = new ArrayList<>();
        for (NetworkDevice device : CableNetwork.at(server, getBlockPos()).devices()) {
            if (device instanceof ExperienceInterfaceBlockEntity bank) {
                found.add(bank);
            }
        }
        return found;
    }

    // ---- a chest's worth of slots ----------------------------------------------------------

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
    public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this, player);
    }

    @Override
    public void clearContent() {
        items.clear();
        setChanged();
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.qbotics.collector");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return ChestMenu.threeRows(containerId, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        ContainerHelper.saveAllItems(output, items);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        items.clear();
        ContainerHelper.loadAllItems(input, items);
    }
}
