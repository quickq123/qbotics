package qbotics.block;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;

/**
 * A Player, Chat or Command Interface (see {@link GameInterfaceBlock}). Which one it is comes
 * from its block. The player who placed it is its owner: the one a Player Interface reaches,
 * and the one a Chat Interface acts for.
 */
public class GameInterfaceBlockEntity extends DeviceBlockEntity {
    /** Chat Interfaces with programs listening, so a chat message finds them. */
    private static final Set<GameInterfaceBlockEntity> HEARING = ConcurrentHashMap.newKeySet();

    /** The owner's name when it was placed, for saying whose it is while they are away. */
    private String ownerName = "";
    /** Programs waiting for chat: computer → its event session. Not saved: a program signs up as it runs. */
    private final Map<UUID, Integer> listeners = new ConcurrentHashMap<>();
    /** What it has written to chat lately, so a program in a loop can't flood it. */
    private long windowStart = Long.MIN_VALUE;
    private int sentInWindow;

    public GameInterfaceBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.GAME_INTERFACE_BLOCK_ENTITY.get(), pos, state);
    }

    @Override
    public Kind kind() {
        return getBlockState().getBlock() instanceof GameInterfaceBlock block ? block.kind : Kind.PLAYER;
    }

    void placedBy(@Nullable Player player) {
        onPlaced(player == null ? null : player.getUUID());
        ownerName = player == null ? "" : player.getName().getString();
        setChanged();
    }

    /** For tests: as if this player had placed it. */
    public void setOwnerForTest(@Nullable UUID owner, String name) {
        onPlaced(owner);
        ownerName = name;
        setChanged();
    }

    /** The owner's name: as they are called now if they are online, else as they were when they placed it. */
    public String ownerName(@Nullable MinecraftServer server) {
        ServerPlayer online = onlineOwner(server);
        return online != null ? online.getName().getString() : ownerName;
    }

    /** The owner, if they are on the server now. */
    public @Nullable ServerPlayer onlineOwner(@Nullable MinecraftServer server) {
        return server == null || owner() == null ? null : server.getPlayerList().getPlayer(owner());
    }

    /** What right-clicking the block says. */
    String status(MinecraftServer server) {
        String address = deviceName().isEmpty() ? ip() : deviceName() + "  " + ip();
        String who = ownerName(server).isEmpty() ? "nobody" : ownerName(server);
        String online = onlineOwner(server) != null ? "online" : "offline";
        return address + "  |  " + switch (kind()) {
            case PLAYER -> owner() == null ? "placed by no player, so it reaches no one" : "reaches " + who + " (" + online + ")";
            case CHAT -> "hears chat; speaks and runs commands as " + who + (owner() == null ? "" : " (" + online + ")");
            default -> owner() == null ? "runs commands for any computer on its network" : "runs commands for " + who + "'s computers";
        };
    }

    // ---- the Chat Interface ------------------------------------------------------------------

    public Map<UUID, Integer> listeners() {
        return listeners;
    }

    public void listen(UUID computer, int session) {
        listeners.put(computer, session);
        HEARING.add(this);
    }

    /** Every Chat Interface a program is listening to now. */
    public static Set<GameInterfaceBlockEntity> hearing() {
        HEARING.removeIf(device -> device.isRemoved() || device.listeners.isEmpty());
        return HEARING;
    }

    /**
     * Counts one message written to chat. Returns false if it has written as many as it may in
     * this second, so the message isn't sent.
     */
    public boolean mayWrite(long gameTime, int perSecond) {
        if (gameTime - windowStart >= 20) {
            windowStart = gameTime;
            sentInWindow = 0;
        }
        if (sentInWindow >= perSecond) {
            return false;
        }
        sentInWindow++;
        return true;
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        HEARING.remove(this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("owner_name", ownerName);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        ownerName = input.getStringOr("owner_name", "");
    }
}
