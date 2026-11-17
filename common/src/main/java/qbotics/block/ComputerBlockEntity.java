package qbotics.block;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponentGetter;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.computer.ComputerManager;
import qbotics.network.Addresses;
import qbotics.network.CableNetwork;
import qbotics.network.NetworkDevice;

/**
 * The placed computer's identity. The running program does not live here: it belongs to
 * the server-wide {@link ComputerManager}, keyed by device ID (design doc §6.2).
 */
public class ComputerBlockEntity extends BlockEntity implements NetworkDevice, qbotics.limits.Limited, qbotics.limits.Limits.Ownable {
    /** Identifies this placed machine. A new one is assigned on every placement (design doc §17.1). */
    private @Nullable UUID deviceId;
    /**
     * Identifies this computer's files. Unlike the device ID it is kept when the block is
     * broken: the dropped item carries it, so the files come back when it is placed again.
     */
    private @Nullable UUID driveId;
    /** The player who placed it. Stored as a UUID because names can change (design doc §5.1). */
    private @Nullable UUID owner;
    /** The name it goes by on its cable network, and the address that network gave it (design doc §9.3). */
    private String name = "";
    private int hostNumber;
    /** Inert: over its owner's limit, or placed by a machine (design doc §5.3). */
    private boolean bricked;
    /**
     * True once it has been switched on, until a program shuts it down: a computer that was
     * on comes back on through the boot queue when its chunk loads (design doc §6.3).
     */
    private boolean on;

    /** Who may log in to this computer from another one with ssh. */
    public enum RemoteLogin {
        /** Only computers of this computer's owner. How every computer starts. */
        OWNER("owner"),
        /** The owner's computers, and anyone who gives the password. */
        PASSWORD("password"),
        OFF("off");

        public final String word;

        RemoteLogin(String word) {
            this.word = word;
        }

        public static @Nullable RemoteLogin parse(String word) {
            for (RemoteLogin login : values()) {
                if (login.word.equals(word)) {
                    return login;
                }
            }
            return null;
        }
    }

    private RemoteLogin remoteLogin = RemoteLogin.OWNER;
    /** Who may open it by right-clicking it: its owner only, the owner's team, or anyone. */
    private qbotics.network.RadioNetwork.Access opens = qbotics.network.RadioNetwork.Access.PRIVATE;

    public qbotics.network.RadioNetwork.Access opens() {
        return opens;
    }

    public void setOpens(qbotics.network.RadioNetwork.Access access) {
        opens = access;
        setChanged();
    }

    /**
     * True if this player may open it (its terminal, files and settings; a Qbot's or Server's
     * screen): its owner, an operator, and whoever its "open" setting lets in. What is typed
     * there runs as the owner, so by default only the owner may.
     */
    public boolean mayOpen(net.minecraft.server.level.ServerPlayer player) {
        var server = player.level().getServer();
        return owner == null || owner.equals(player.getUUID()) || server.getPlayerList().isOp(player.nameAndId())
                || qbotics.network.RadioNetwork.allowed(server, opens, owner, player.getUUID());
    }

    /** What a player who may not open it is told. */
    public static net.minecraft.network.chat.Component refusal() {
        return net.minecraft.network.chat.Component.literal(
                "This isn't yours to open. Its owner can let their team or everyone in: its settings page, \"open\".");
    }
    /** The password is not kept: only a scrambled form that a guess can be checked against. Empty for none. */
    private String passwordHash = "";
    private String passwordSalt = "";

    public RemoteLogin remoteLogin() {
        return remoteLogin;
    }

    public void setRemoteLogin(RemoteLogin login) {
        remoteLogin = login;
        setChanged();
    }

    public boolean hasPassword() {
        return !passwordHash.isEmpty();
    }

    /** Sets the password; an empty one removes it. */
    public void setPassword(String password) {
        if (password.isEmpty()) {
            passwordHash = "";
            passwordSalt = "";
        } else {
            passwordSalt = UUID.randomUUID().toString();
            passwordHash = scramble(passwordSalt, password);
        }
        setChanged();
    }

    public boolean checkPassword(String guess) {
        return hasPassword() && java.security.MessageDigest.isEqual(
                passwordHash.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                scramble(passwordSalt, guess).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String scramble(String salt, String password) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest((salt + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public ComputerBlockEntity(BlockPos pos, BlockState state) {
        this(QboticsContent.COMPUTER_BLOCK_ENTITY.get(), pos, state);
    }

    /** For kinds of computer with a block entity of their own (the Qbot). */
    protected ComputerBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
        CableNetwork.changed(pos);
    }

    /**
     * True while a Qbot steps from one block to the next: its old block going and its new one
     * arriving are the same computer moving, not one broken and another placed. Server thread only.
     */
    public static boolean moving;

    public void onPlaced(@Nullable UUID placedBy) {
        deviceId = UUID.randomUUID();
        owner = placedBy;
        on = false;
        setChanged();
    }

    // ---- limits and booting (design doc §5, §6.3) ---------------------------------------------

    @Override
    public String limitType() {
        return qbotics.limits.Limits.COMPUTER;
    }

    @Override
    public boolean bricked() {
        return bricked;
    }

    @Override
    public void setBricked(boolean value) {
        if (bricked == value) {
            return;
        }
        bricked = value;
        setChanged();
        CableNetwork.changed(getBlockPos()); // a bricked computer is not on its network
        if (level != null && getBlockState().hasProperty(ComputerBlock.ACTIVE)) {
            level.setBlock(getBlockPos(), getBlockState().setValue(ComputerBlock.ACTIVE, !value), net.minecraft.world.level.block.Block.UPDATE_ALL);
        }
        if (value && level instanceof ServerLevel serverLevel) {
            ComputerManager.get(serverLevel.getServer()).remove(deviceId()); // its program stops
        }
    }

    @Override
    public void claim(UUID newOwner) {
        owner = newOwner;
        setChanged();
    }

    /** True if it was on when last seen, so it comes back on when loaded. */
    public boolean wasOn() {
        return on;
    }

    /** Remembers whether it is switched on: started, or shut down by its program. */
    public void setOn(boolean value) {
        if (on != value) {
            on = value;
            setChanged();
        }
    }

    /** It has been loaded (or placed): it counts, and if it was on it comes back on. */
    @Override
    public void setLevel(net.minecraft.world.level.Level newLevel) {
        super.setLevel(newLevel);
        if (newLevel instanceof ServerLevel serverLevel && !moving) {
            qbotics.limits.Limits.loaded(serverLevel, this);
            if (on && !bricked) {
                ComputerManager.get(serverLevel.getServer()).queueBoot(serverLevel, this);
            }
        }
    }

    @Override
    public UUID deviceId() {
        if (deviceId == null) {
            // Placed by something other than a player (a command, a structure).
            deviceId = UUID.randomUUID();
            setChanged();
        }
        return deviceId;
    }

    public UUID driveId() {
        if (driveId == null) {
            driveId = UUID.randomUUID();
            setChanged();
        }
        return driveId;
    }

    /** Gives this computer a new, empty drive. */
    public void replaceDrive() {
        driveId = UUID.randomUUID();
        setChanged();
    }

    public @Nullable UUID owner() {
        return owner;
    }

    /** Hands the computer to another player (or to nobody). Its identity and files stay as they are. */
    public void setOwner(@Nullable UUID newOwner) {
        owner = newOwner;
        setChanged();
        if (level instanceof ServerLevel serverLevel) {
            qbotics.limits.Limits.ownerChanged(serverLevel, this);
        }
    }

    @Override
    public Kind kind() {
        return Kind.COMPUTER;
    }

    @Override
    public String deviceName() {
        return name;
    }

    @Override
    public void setDeviceName(String newName) {
        name = Addresses.cleanName(newName);
        setChanged();
    }

    @Override
    public int hostNumber() {
        return hostNumber;
    }

    @Override
    public void setHostNumber(int number) {
        if (hostNumber != number) {
            hostNumber = number;
            setChanged();
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        CableNetwork.changed(getBlockPos());
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        // The block was broken or replaced (not merely unloaded): the computer is gone.
        if (deviceId != null && level instanceof ServerLevel serverLevel && !moving) {
            ComputerManager.get(serverLevel.getServer()).remove(deviceId);
            qbotics.limits.Limits.removed(serverLevel, this);
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.storeNullable("device_id", UUIDUtil.CODEC, deviceId);
        output.storeNullable("owner", UUIDUtil.CODEC, owner);
        output.storeNullable("drive_id", UUIDUtil.CODEC, driveId);
        output.putString("name", name);
        output.putInt("host", hostNumber);
        output.putString("remote_login", remoteLogin.word);
        output.putString("open", opens.word);
        output.putString("password_hash", passwordHash);
        output.putString("password_salt", passwordSalt);
        output.putBoolean("bricked", bricked);
        output.putBoolean("on", on);
    }

    // The drive ID moves between the block and its item as a data component.

    @Override
    protected void applyImplicitComponents(DataComponentGetter components) {
        super.applyImplicitComponents(components);
        driveId = components.get(QboticsContent.DRIVE_ID.get());
        Component custom = components.get(DataComponents.CUSTOM_NAME);
        if (custom != null) {
            name = Addresses.cleanName(custom.getString());
        }
    }

    @Override
    protected void collectImplicitComponents(DataComponentMap.Builder components) {
        super.collectImplicitComponents(components);
        if (driveId != null) {
            components.set(QboticsContent.DRIVE_ID.get(), driveId);
        }
        if (!name.isEmpty()) {
            components.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        }
    }

    @Override
    public void removeComponentsFromTag(ValueOutput output) {
        super.removeComponentsFromTag(output);
        output.discard("drive_id");
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        deviceId = input.read("device_id", UUIDUtil.CODEC).orElse(null);
        owner = input.read("owner", UUIDUtil.CODEC).orElse(null);
        driveId = input.read("drive_id", UUIDUtil.CODEC).orElse(null);
        name = Addresses.cleanName(input.getStringOr("name", ""));
        hostNumber = input.getIntOr("host", 0);
        RemoteLogin saved = RemoteLogin.parse(input.getStringOr("remote_login", ""));
        remoteLogin = saved == null ? RemoteLogin.OWNER : saved;
        qbotics.network.RadioNetwork.Access open = qbotics.network.RadioNetwork.Access.parse(input.getStringOr("open", ""));
        opens = open == null ? qbotics.network.RadioNetwork.Access.PRIVATE : open;
        passwordHash = input.getStringOr("password_hash", "");
        passwordSalt = input.getStringOr("password_salt", "");
        bricked = input.getBooleanOr("bricked", false);
        on = input.getBooleanOr("on", false);
    }
}
