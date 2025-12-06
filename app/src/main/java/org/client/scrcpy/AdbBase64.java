package org.client.scrcpy.adb;

public interface AdbBase64 {
    byte[] decode(byte[] data);
    String encodeToString(byte[] data);
}