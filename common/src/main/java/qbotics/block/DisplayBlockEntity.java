package qbotics.block;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;
import qbotics.QboticsContent;
import qbotics.display.Displays;
import qbotics.display.PanelScreen;
import qbotics.display.ScreenContent;
import qbotics.display.TextScreen;

/**
 * One block of a display: a Text Monitor or a Control Panel (design doc §10). Blocks of one
 * kind, side by side and facing the same way, make one screen up to 8 blocks wide and 6 high
 * (see {@link Displays#reform}). The screen's top-left block is the network device and holds
 * what the screen shows; the others are parts of it.
 */
public class DisplayBlockEntity extends DeviceBlockEntity {
    public static final int MAX_WIDTH = 8;
    public static final int MAX_HEIGHT = 6;

    /** Where this block is in its screen (0, 0 is the top left), and the screen's size, in blocks. */
    private int col;
    private int row;
    private int width = 1;
    private int height = 1;
    /** What the screen shows: only on the server, and only in the top-left block. */
    private @Nullable ScreenContent content;

    public DisplayBlockEntity(BlockPos pos, BlockState state) {
        super(QboticsContent.DISPLAY_BLOCK_ENTITY.get(), pos, state);
    }

    /** False once it has taken over a screen's identity: placing it then keeps that identity. */
    private boolean fresh = true;

    @Override
    public void onPlaced(java.util.@Nullable UUID placedBy) {
        if (fresh) {
            super.onPlaced(placedBy);
        }
    }

    public boolean isPanel() {
        return getBlockState().getBlock() instanceof DisplayBlock block && block.panel;
    }

    @Override
    public Kind kind() {
        return isPanel() ? Kind.PANEL : Kind.MONITOR;
    }

    /** Only the top-left block is a device on the network; the rest of the screen joins it to cables. */
    @Override
    public boolean listed() {
        return content != null;
    }

    /** The screen's face doesn't join the network. */
    @Override
    public boolean connectsOn(Direction side) {
        return side != facing();
    }

    public Direction facing() {
        return getBlockState().hasProperty(DisplayBlock.FACING) ? getBlockState().getValue(DisplayBlock.FACING) : Direction.NORTH;
    }

    public int col() {
        return col;
    }

    public int row() {
        return row;
    }

    public int screenWidth() {
        return width;
    }

    public int screenHeight() {
        return height;
    }

    public boolean isOrigin() {
        return col == 0 && row == 0;
    }

    public @Nullable ScreenContent content() {
        return content;
    }

    /** The screen's top-left block, where its identity and content are. */
    public BlockPos originPos() {
        Direction right = facing().getCounterClockWise();
        return getBlockPos().relative(right, -col).above(row);
    }

    /** Places this block in a screen; the top-left block gets the content. Server only. */
    public void layOut(int newCol, int newRow, int newWidth, int newHeight, @Nullable ScreenContent newContent) {
        boolean moved = newCol != col || newRow != row || newWidth != width || newHeight != height || newContent != content;
        col = newCol;
        row = newRow;
        width = newWidth;
        height = newHeight;
        if (content != null && content != newContent) {
            Displays.forget(this);
        }
        content = newContent;
        if (content != null) {
            content.resize(width, height);
            Displays.remember(this);
        }
        if (moved) {
            setChanged();
            if (level != null && !level.isClientSide()) {
                level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), net.minecraft.world.level.block.Block.UPDATE_CLIENTS);
            }
            qbotics.network.CableNetwork.changed();
        }
    }

    /** Hands this block's identity and content to another block of its screen (this one is going). */
    void handOver(DisplayBlockEntity heir) {
        heir.takeIdentity(this);
        heir.content = content;
        content = null;
        Displays.forget(this);
    }

    /** Takes over a block's identity and content as the new top-left block of a screen. */
    public void adopt(DisplayBlockEntity from) {
        if (from == this) {
            return;
        }
        takeIdentity(from);
        fresh = false;
        content = from.content;
        from.content = null;
        from.freshIdentity();
        Displays.forget(from);
    }

    /** A new, blank screen for a top-left block that had none. */
    public ScreenContent newContent() {
        return isPanel() ? new PanelScreen() : new TextScreen();
    }

    /** The content this block had when it was a screen's top-left block, given up as it becomes a part. */
    public void dropContent() {
        if (content != null) {
            content.markGone();
            Displays.forget(this);
            content = null;
            freshIdentity();
        }
    }

    @Override
    public void setLevel(Level newLevel) {
        super.setLevel(newLevel);
        if (!newLevel.isClientSide() && content != null) {
            Displays.remember(this);
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (content != null) {
            content.markGone();
            Displays.forget(this);
        }
    }

    /** Breaking the top-left block: the screen's identity and content go to a block that stays. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        super.preRemoveSideEffects(pos, state);
        if (content != null && level != null) {
            Direction right = facing().getCounterClockWise();
            for (BlockPos next : new BlockPos[] {pos.relative(right), pos.below()}) {
                if (level.getBlockEntity(next) instanceof DisplayBlockEntity other && other.getBlockState().getBlock() == getBlockState().getBlock()
                        && other.facing() == facing() && other.originPos().equals(pos)) {
                    handOver(other);
                    break;
                }
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("col", col);
        output.putInt("row", row);
        output.putInt("width", width);
        output.putInt("height", height);
        if (content != null) {
            output.putString("screen", content.save().toString());
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        col = input.getIntOr("col", 0);
        row = input.getIntOr("row", 0);
        width = Math.clamp(input.getIntOr("width", 1), 1, MAX_WIDTH);
        height = Math.clamp(input.getIntOr("height", 1), 1, MAX_HEIGHT);
        String saved = input.getStringOr("screen", "");
        if (!saved.isEmpty() && content == null) {
            content = newContent();
            try {
                content.load(JsonParser.parseString(saved).getAsJsonObject());
            } catch (RuntimeException e) {
                qbotics.Qbotics.LOGGER.warn("A display's saved screen couldn't be read; it starts blank", e);
            }
            content.resize(width, height);
        }
    }

    // Players' games are told where each block is in its screen; what it shows comes separately (DisplayPayloads).
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("col", col);
        tag.putInt("row", row);
        tag.putInt("width", width);
        tag.putInt("height", height);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** For the probe and messages. */
    public JsonObject describe() {
        JsonObject info = new JsonObject();
        info.addProperty("width", width);
        info.addProperty("height", height);
        if (content instanceof TextScreen text) {
            info.addProperty("cols", text.cols());
            info.addProperty("rows", text.rows());
        } else if (content instanceof PanelScreen panel) {
            info.addProperty("cols", panel.cols());
            info.addProperty("rows", panel.rows());
        }
        return info;
    }
}
