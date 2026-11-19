package android.system;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Host-only double. Android runtime checks must use the actual platform Os. */
public final class Os {
    public static boolean failNextRename;

    public static void rename(String source, String target) throws ErrnoException {
        if (failNextRename) {
            failNextRename = false;
            throw new ErrnoException("rename", 5);
        }
        try {
            Files.move(Path.of(source), Path.of(target), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ErrnoException("rename", 5);
        }
    }
}
