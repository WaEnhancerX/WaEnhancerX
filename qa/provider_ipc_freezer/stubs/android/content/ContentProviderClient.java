package android.content;

import android.os.Bundle;
import android.os.RemoteException;

public class ContentProviderClient implements AutoCloseable {
    public Bundle answer;
    public Throwable failure;
    public int calls;
    public int closes;
    public String lastMethod;
    public String lastArgument;
    public Bundle lastExtras;

    public Bundle call(String method, String arg, Bundle extras) throws RemoteException {
        calls++;
        lastMethod = method;
        lastArgument = arg;
        lastExtras = extras;
        if (failure instanceof RemoteException) throw (RemoteException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        return answer;
    }

    @Override public void close() { closes++; }
}
