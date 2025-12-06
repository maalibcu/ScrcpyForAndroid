package org.client.scrcpy.adb;

import java.io.IOException;
import java.nio.ByteBuffer;

public interface AdbChannel {
    void write(ByteBuffer data) throws IOException;
    ByteBuffer read(int size) throws InterruptedException, IOException;
    void flush();
    void close();
}