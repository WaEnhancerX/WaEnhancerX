package android.system;

public class ErrnoException extends Exception {
    public ErrnoException(String function, int errno) {
        super(function + " failed: errno=" + errno);
    }
}
