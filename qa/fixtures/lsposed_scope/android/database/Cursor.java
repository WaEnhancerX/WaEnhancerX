package android.database;

import java.io.Closeable;

public interface Cursor extends Closeable {
    boolean moveToFirst();
    boolean moveToNext();
    String getString(int column);
    int getInt(int column);
    @Override void close();
}
