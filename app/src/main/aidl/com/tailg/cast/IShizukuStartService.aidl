package com.tailg.cast;

import android.os.ParcelFileDescriptor;
import android.os.ResultReceiver;

interface IShizukuStartService {
    void start(
        String commandLine,
        in ParcelFileDescriptor stdout,
        in ParcelFileDescriptor stderr,
        in ResultReceiver resultReceiver
    ) = 1;
    void destroy() = 16777114;
}
