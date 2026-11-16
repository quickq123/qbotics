package qbotics.net;

import io.netty.buffer.ByteBuf;
import java.util.UUID;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import qbotics.Qbotics;

/** The editor's messages: file operations, scratchpad runs and "open this file". */
public final class IdePayloads {
    /**
     * The largest file the editor will open or save, in bytes. A message from the client
     * to the server can't be much larger than this.
     */
    public static final int MAX_FILE_BYTES = 30_000;
    public static final int MAX_PATH_LENGTH = 1024;
    public static final int MAX_SCRATCH_CODE = 8000;
    public static final int MAX_SCRATCH_OUTPUT = 8000;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private IdePayloads() {
    }

    /**
     * Client to server: do something to the files of the computer whose screen is open.
     *
     * @param requestId echoed in the response so the client can match them up
     * @param data      the bytes to write for WRITE; the new path (UTF-8) for RENAME
     */
    public record FileRequest(UUID deviceId, int requestId, byte op, String path, byte[] data) implements CustomPacketPayload {
        public static final byte LIST = 1;
        public static final byte READ = 2;
        public static final byte WRITE = 3;
        public static final byte MKDIR = 4;
        /** Deletes a file, or a folder and everything in it. */
        public static final byte DELETE = 5;
        public static final byte RENAME = 6;
        /** Creates an empty file; fails if the name is taken. */
        public static final byte CREATE = 7;
        /**
         * Compiles the Python in data (the file at path, as the editor has it, saved or not)
         * without running it. The answer is "" if it compiles, otherwise "line\tmessage".
         */
        public static final byte CHECK = 8;
        /** The devices on the computer's network, as the devices call gives them (JSON), for the editor's suggestions. */
        public static final byte DEVICES = 9;

        public static final Type<FileRequest> TYPE = new Type<>(Qbotics.id("ide_file_request"));
        public static final StreamCodec<ByteBuf, FileRequest> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, FileRequest::deviceId,
                ByteBufCodecs.VAR_INT, FileRequest::requestId,
                ByteBufCodecs.BYTE, FileRequest::op,
                ByteBufCodecs.stringUtf8(MAX_PATH_LENGTH), FileRequest::path,
                ByteBufCodecs.byteArray(MAX_FILE_BYTES), FileRequest::data,
                FileRequest::new);

        @Override
        public Type<FileRequest> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: the outcome of a {@link FileRequest}.
     *
     * @param errno 0 for success, otherwise a POSIX error number
     * @param data  the file for READ; the folder listing for LIST (lines of kind, size, name
     *              separated by tabs)
     */
    public record FileResponse(UUID deviceId, int requestId, int errno, byte[] data) implements CustomPacketPayload {
        public static final Type<FileResponse> TYPE = new Type<>(Qbotics.id("ide_file_response"));
        public static final StreamCodec<ByteBuf, FileResponse> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, FileResponse::deviceId,
                ByteBufCodecs.VAR_INT, FileResponse::requestId,
                ByteBufCodecs.VAR_INT, FileResponse::errno,
                ByteBufCodecs.byteArray(MAX_RESPONSE_BYTES), FileResponse::data,
                FileResponse::new);

        @Override
        public Type<FileResponse> type() {
            return TYPE;
        }
    }

    /** Client to server: run this code from the scratchpad. */
    public record ScratchRun(UUID deviceId, int runId, String code) implements CustomPacketPayload {
        public static final Type<ScratchRun> TYPE = new Type<>(Qbotics.id("ide_scratch_run"));
        public static final StreamCodec<ByteBuf, ScratchRun> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ScratchRun::deviceId,
                ByteBufCodecs.VAR_INT, ScratchRun::runId,
                ByteBufCodecs.stringUtf8(MAX_SCRATCH_CODE), ScratchRun::code,
                ScratchRun::new);

        @Override
        public Type<ScratchRun> type() {
            return TYPE;
        }
    }

    /** Server to client: what a scratchpad run printed. */
    public record ScratchResult(UUID deviceId, int runId, boolean ok, String output) implements CustomPacketPayload {
        public static final Type<ScratchResult> TYPE = new Type<>(Qbotics.id("ide_scratch_result"));
        public static final StreamCodec<ByteBuf, ScratchResult> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, ScratchResult::deviceId,
                ByteBufCodecs.VAR_INT, ScratchResult::runId,
                ByteBufCodecs.BOOL, ScratchResult::ok,
                ByteBufCodecs.stringUtf8(MAX_SCRATCH_OUTPUT), ScratchResult::output,
                ScratchResult::new);

        @Override
        public Type<ScratchResult> type() {
            return TYPE;
        }
    }

    /** Server to client: the "edit" shell command asks the editor to open a file. */
    /**
     * Server to client: open the editor on a drive that belongs to no computer (a
     * Programmer's), with only its files: no terminal, nothing to run.
     */
    public record OpenEditor(UUID deviceId, String title) implements CustomPacketPayload {
        public static final Type<OpenEditor> TYPE = new Type<>(Qbotics.id("ide_open_editor"));
        public static final StreamCodec<ByteBuf, OpenEditor> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, OpenEditor::deviceId,
                ByteBufCodecs.stringUtf8(64), OpenEditor::title,
                OpenEditor::new);

        @Override
        public Type<OpenEditor> type() {
            return TYPE;
        }
    }

    /** The computer's files changed other than through the editor (the shell, a program): reload the explorer. */
    public record FilesChanged(UUID deviceId) implements CustomPacketPayload {
        public static final Type<FilesChanged> TYPE = new Type<>(Qbotics.id("ide_files_changed"));
        public static final StreamCodec<ByteBuf, FilesChanged> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, FilesChanged::deviceId,
                FilesChanged::new);

        @Override
        public Type<FilesChanged> type() {
            return TYPE;
        }
    }

    public record OpenFile(UUID deviceId, String path) implements CustomPacketPayload {
        public static final Type<OpenFile> TYPE = new Type<>(Qbotics.id("ide_open_file"));
        public static final StreamCodec<ByteBuf, OpenFile> STREAM_CODEC = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, OpenFile::deviceId,
                ByteBufCodecs.stringUtf8(MAX_PATH_LENGTH), OpenFile::path,
                OpenFile::new);

        @Override
        public Type<OpenFile> type() {
            return TYPE;
        }
    }
}
