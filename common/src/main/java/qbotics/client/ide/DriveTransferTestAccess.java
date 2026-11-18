package qbotics.client.ide;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The game tests' way in to DriveTransfer's checks, which are kept to this package otherwise. */
public final class DriveTransferTestAccess {
    private DriveTransferTestAccess() {
    }

    /** What a .zip would load: its files by path, what was left out, and the new folder's name. */
    public record Plan(String name, java.util.Map<String, byte[]> files, List<String> skipped) {
    }

    public static @Nullable List<String> safeParts(String raw, List<String> reasons) {
        return DriveTransfer.safeParts(raw, reasons);
    }

    public static String folderName(String from) {
        return DriveTransfer.folderName(from);
    }

    public static Plan fromZip(Path zip) throws IOException {
        DriveTransfer.Plan plan = DriveTransfer.fromZip(zip);
        return new Plan(plan.name(), plan.files(), plan.skipped());
    }
}
