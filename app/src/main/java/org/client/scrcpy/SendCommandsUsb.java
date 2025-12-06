package org.client.scrcpy;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.util.Log;



import java.io.File;
import java.io.FileInputStream;

public class SendCommandsUsb {
    private static final String TAG = "SendCommandsUsb";
    
    public int sendAdbCommandsUsb(Context context, UsbDevice usbDevice, 
                                   int forwardPort, String localIp, 
                                   int bitrate, int size) {
        try {
            // Get or create ADB key pair
            File privateKeyFile = new File(context.getFilesDir(), "adb_key");
            File publicKeyFile = new File(context.getFilesDir(), "adb_key.pub");
            
            if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
                Log.i(TAG, "Generating ADB key pair");
                AdbKeyPair.generate(publicKeyFile, privateKeyFile);
            }
            
            // Set base64 encoder for ADB
            AdbKeyPair.setAdbBase64(new AdbBase64() {
                @Override
                public byte[] decode(byte[] data) {
                    return android.util.Base64.decode(data, android.util.Base64.DEFAULT);
                }

                @Override
                public String encodeToString(byte[] data) {
                    return android.util.Base64.encodeToString(data, android.util.Base64.DEFAULT);
                }
            });
            
            // Read key pair
            AdbKeyPair keyPair = AdbKeyPair.read(publicKeyFile, privateKeyFile);
            
            // Connect via USB
            Log.i(TAG, "Connecting to USB device");
            Adb adb = new Adb(usbDevice, keyPair);
            
            // Push scrcpy server
            Log.i(TAG, "Pushing scrcpy-server.jar");
            File serverFile = new File(context.getExternalFilesDir("scrcpy"), "scrcpy-server.jar");
            FileInputStream fis = new FileInputStream(serverFile);
            
            adb.pushFile(fis, "/data/local/tmp/scrcpy-server.jar", process -> {
                Log.d(TAG, "Push progress: " + process + "%");
            });
            
            // Setup port forwarding
            Log.i(TAG, "Setting up port forwarding");
            BufferStream forwardStream = adb.tcpForward(forwardPort);
            
            // Start scrcpy server
            Log.i(TAG, "Starting scrcpy server");
            String cmd = String.format(
                "CLASSPATH=/data/local/tmp/scrcpy-server.jar " +
                "app_process / org.server.scrcpy.Server " +
                "/%s %d %d",
                localIp, size, bitrate
            );
            
            adb.runAdbCmd(cmd);
            
            Log.i(TAG, "USB ADB commands completed successfully");
            return 0;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to execute USB ADB commands", e);
            return 1;
        }
    }
}