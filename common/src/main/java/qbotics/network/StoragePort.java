package qbotics.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.world.item.ItemStack;

/**
 * Many containers used as one: a storage group (logistics.Storage, docs/reference.md §6.4c).
 * Its slots are its members' slots one after another, in member order. Items are taken from
 * the members in order. They are put first into a member that chooses them (a bus whose
 * filter lets them in, an interface), where that kind already is and then anywhere such a
 * member has room; then into members with no filter, again where the kind already is first.
 * So a kind gathers in a few places instead of spreading over every empty slot.
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

    /** True for a member that chooses what it takes: a bus with a filter, or an interface (its slot filters). */
    private static boolean chooses(Member member) {
        return member.port instanceof BusFilter.Filtered || member.port instanceof qbotics.block.InterfaceBlockEntity;
    }

    /** True if a member that chooses lets this kind in. */
    private static boolean lets(Member member, ItemStack like) {
        if (member.port instanceof BusFilter.Filtered filtered) {
            return filtered.allows(like);
        }
        return member.port.insert(like, 1, true) > 0;
    }

    @Override
    public int insert(ItemStack like, int max, boolean simulate) {
        int done = 0;
        boolean[] tried = new boolean[members.size()];
        boolean[] chooses = new boolean[members.size()];
        for (int i = 0; i < members.size(); i++) {
            chooses[i] = chooses(members.get(i));
        }
        // 0: a member whose filter lets it in and that holds some; 1: one whose filter lets it in;
        // 2: one with no filter that holds some; 3: one with no filter (the overflow). A member
        // whose filter doesn't let it in never gets it, even if some is there already.
        for (int pass = 0; pass < 4 && done < max; pass++) {
            for (int i = 0; i < members.size() && done < max; i++) {
                Member member = members.get(i);
                if (tried[i] || chooses[i] != pass < 2 || (pass % 2 == 0 && !holds(member, like)) || (chooses[i] && !lets(member, like))) {
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
