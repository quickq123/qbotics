package qbotics.network;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.WorldlyContainer;

/**
 * The item slots of a fluid or energy store, as a device of their own for programs: a Fluid
 * Interface's bucket slots ({@code tanks.buckets}) or an Energy Interface's battery slots
 * ({@code cell.batteries}). Its address is the store's, then "@item". In move() it takes and
 * gives items by the same rules as a hopper on the block: a bucket goes in only where its
 * tank can fill or empty it, a battery only into the slot that suits it, and a battery comes
 * out only when done.
 */
public final class SlotsView implements NetworkDevice {
    private final NetworkDevice store;
    private final WorldlyContainer slots;
    private final String kindWord;

    public SlotsView(NetworkDevice store, WorldlyContainer slots, String kindWord) {
        this.store = store;
        this.slots = slots;
        this.kindWord = kindWord;
    }

    /** The store these are the slots of. */
    public NetworkDevice store() {
        return store;
    }

    /** The slots as a place items move to and from, by a hopper's rules. */
    public ItemPort port() {
        return ContainerItemPort.asAutomation(slots);
    }

    @Override
    public UUID deviceId() {
        return store.deviceId();
    }

    @Override
    public Kind kind() {
        return store.kind();
    }

    @Override
    public String kindWord() {
        return kindWord;
    }

    @Override
    public String mac() {
        return store.mac() + "@" + Medium.ITEM.word;
    }

    @Override
    public String deviceName() {
        return store.deviceName();
    }

    @Override
    public void setDeviceName(String name) {
        store.setDeviceName(name);
    }

    @Override
    public int hostNumber() {
        return store.hostNumber();
    }

    @Override
    public void setHostNumber(int number) {
        // the store's own address is the one that is kept
    }

    @Override
    public BlockPos getBlockPos() {
        return store.getBlockPos();
    }

    @Override
    public boolean isRemoved() {
        return store.isRemoved();
    }

    @Override
    public boolean listed() {
        return false;
    }
}
