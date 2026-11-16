package qbotics.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

/**
 * Many containers used as one: a storage group (logistics.Storage, docs/reference.md §6.4c).
 * Its slots are its members' slots one after another, in member order. Items are taken from
 * the members in order; they are put first where that kind already is, so a kind gathers in
 * a few places instead of spreading over every empty slot, and then into the first member
 * with room.
 *
 * <p>Made fresh for each call, on the server thread.
 */
final class StoragePort implements ItemPort {
    /** One container of the group: the device that reaches it, the container, and where it is (see NetworkCalls.places). */
    static final class Member {
        final NetworkDevice device;
        final ItemPort port;
        final Set<String> places;
        /** What it held when last read for a put, or null when it must be read again. */
        List<ItemStack> seen;

        Member(NetworkDevice device, ItemPort port, Set<String> places) {
            this.device = device;
            this.port = port;
            this.places = places;
        }
    }

    private final List<Member> members;

    StoragePort(List<Member> members) {
        this.members = members;
    }

    List<Member> members() {
        return members;
    }

    /** The same group without any member at these places: a move between the group and one of its own members leaves that member out. */
    StoragePort without(Set<String> places) {
        List<Member> kept = new ArrayList<>();
        for (Member member : members) {
            Set<String> shared = new HashSet<>(member.places);
            shared.retainAll(places);
            if (shared.isEmpty()) {
                kept.add(member);
            }
        }
        return kept.size() == members.size() ? this : new StoragePort(kept);
    }

    @Override
    public List<ItemStack> contents() {
        List<ItemStack> all = new ArrayList<>();
        for (Member member : members) {
            all.addAll(member.port.contents());
        }
        return all;
    }

    private static boolean holds(Member member, ItemStack like) {
        if (member.seen == null) {
            member.seen = member.port.contents();
        }
        for (ItemStack stack : member.seen) {
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, like)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int insert(ItemStack like, int max, boolean simulate) {
        int done = 0;
        boolean[] tried = new boolean[members.size()];
        // First where some of it already is, then anywhere with room.
        for (int pass = 0; pass < 2 && done < max; pass++) {
            for (int i = 0; i < members.size() && done < max; i++) {
                Member member = members.get(i);
                if (tried[i] || (pass == 0 && !holds(member, like))) {
                    continue;
                }
                tried[i] = true;
                int put = member.port.insert(like, max - done, simulate);
                if (put > 0 && !simulate) {
                    member.seen = null;
                }
                done += put;
            }
        }
        return done;
    }

    @Override
    public int extract(ItemStack like, int max, boolean simulate) {
        int done = 0;
        for (Member member : members) {
            if (done >= max) {
                break;
            }
            int taken = member.port.extract(like, max - done, simulate);
            if (taken > 0 && !simulate) {
                member.seen = null;
            }
            done += taken;
        }
        return done;
    }

    @Override
    public int insertInto(int slot, ItemStack like, int max, boolean simulate) {
        for (Member member : members) {
            int size = member.port.contents().size();
            if (slot < size) {
                int put = member.port.insertInto(slot, like, max, simulate);
                if (put > 0 && !simulate) {
                    member.seen = null;
                }
                return put;
            }
            slot -= size;
        }
        return 0;
    }

    @Override
    public int extractFrom(int slot, ItemStack like, int max, boolean simulate) {
        for (Member member : members) {
            int size = member.port.contents().size();
            if (slot < size) {
                int taken = member.port.extractFrom(slot, like, max, simulate);
                if (taken > 0 && !simulate) {
                    member.seen = null;
                }
                return taken;
            }
            slot -= size;
        }
        return 0;
    }
}
