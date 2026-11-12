package android.database;

import java.io.Closeable;

public interface Cursor extends Closeable {
    boolean moveToNext();
    String getString(int column);
    @Override void close();
}
