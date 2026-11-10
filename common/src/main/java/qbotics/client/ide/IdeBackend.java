package qbotics.client.ide;

/**
 * Everything the editor screen asks of the computer it is showing. The real one talks to
 * the server; a stand-in with sample data lets the screen be previewed without a world.
 */
interface IdeBackend {
    interface FileCallback {
        /** @param errno 0 for success, otherwise a POSIX error number */
        void done(int errno, byte[] data);
    }

    interface ScratchCallback {
        void done(boolean ok, String output);
    }

    /** A file operation; op is one of the constants in IdePayloads.FileRequest. */
    void file(byte op, String path, byte[] data, FileCallback callback);

    /** Something typed or pressed in the terminal; action is a TerminalInputPayload constant. */
    void terminalInput(byte action, String line);

    void runScratch(String code, ScratchCallback callback);
}
