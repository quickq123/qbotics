package qbotics.limits;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import qbotics.Qbotics;

/**
 * Every active limited device on the server, with its owner (design doc §5.1). Saved with the
 * world rather than kept in chunks, so a device counts against its owner whether or not its
 * chunk is loaded. Bricked devices are not in it.
 */
public final class DeviceRegistry extends SavedData {
    /** One active device: whose it is, what kind, and where. */
    public record Entry(UUID device, UUID owner, String type, String dimension, BlockPos pos) {
        static final Codec<Entry> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                UUIDUtil.CODEC.fieldOf("device").forGetter(Entry::device),
                UUIDUtil.CODEC.fieldOf("owner").forGetter(Entry::owner),
                Codec.STRING.fieldOf("type").forGetter(Entry::type),
                Codec.STRING.fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos)).apply(instance, Entry::new));
    }

    private static final Codec<DeviceRegistry> CODEC = Entry.CODEC.listOf()
            .xmap(DeviceRegistry::new, registry -> List.copyOf(registry.entries.values()));
    // No data fixer: this data is the mod's own. NeoForge allows null here; a Fabric port must check.
    private static final SavedDataType<DeviceRegistry> TYPE =
            new SavedDataType<>(Qbotics.id("devices"), DeviceRegistry::new, CODEC, null);

    private final Map<UUID, Entry> entries = new HashMap<>();

    private DeviceRegistry() {
    }

    private DeviceRegistry(List<Entry> saved) {
        for (Entry entry : saved) {
            entries.put(entry.device(), entry);
        }
    }

    public static DeviceRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public boolean has(UUID device) {
        return entries.containsKey(device);
    }

    public void put(Entry entry) {
        if (!entry.equals(entries.put(entry.device(), entry))) {
            setDirty();
        }
    }

    public void remove(UUID device) {
        if (entries.remove(device) != null) {
            setDirty();
        }
    }

    /** How many active devices of a type a player owns. */
    public int count(UUID owner, String type) {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (entry.owner().equals(owner) && entry.type().equals(type)) {
                count++;
            }
        }
        return count;
    }

    /** A player's active devices, or everyone's with null. */
    public List<Entry> entries(UUID owner) {
        List<Entry> found = new ArrayList<>();
        for (Entry entry : entries.values()) {
            if (owner == null || entry.owner().equals(owner)) {
                found.add(entry);
            }
        }
        return found;
    }
}
