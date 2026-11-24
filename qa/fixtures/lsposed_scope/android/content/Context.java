package android.content;

import java.io.File;

public class Context {
    private final File cacheDir;

    public Context(File cacheDir) {
        this.cacheDir = cacheDir;
    }

    public File getCacheDir() {
        return cacheDir;
    }
}
