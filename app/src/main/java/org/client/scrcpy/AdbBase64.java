package org.client.scrcpy;

public interface AdbBase64 {
    byte[] decode(byte[] data);
    String encodeToString(byte[] data);
}