package org.client.scrcpy;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.util.Log;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class UsbBroadcastReceiver extends BroadcastReceiver {
    private static final String TAG = "UsbReceiver";
    private static final String ACTION_USB_PERMISSION = "org.client.scrcpy.USB_PERMISSION";
    
    private final ConcurrentHashMap<String, UsbDevice> usbDevices = new ConcurrentHashMap<>();
    private UsbDeviceCallback callback;

    public interface UsbDeviceCallback {
        void onUsbDeviceAttached(UsbDevice device);
        void onUsbDeviceDetached(UsbDevice device);
        void onUsbDevicePermissionGranted(UsbDevice device);
    }

    public void setCallback(UsbDeviceCallback callback) {
        this.callback = callback;
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    public void register(Context context) {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(this, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(this, filter);
        }
        
        // Scan for existing devices
        updateUsbDevices(context);
    }

    public void unregister(Context context) {
        try {
            context.unregisterReceiver(this);
        } catch (Exception e) {
            Log.e(TAG, "Error unregistering receiver", e);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        switch (action) {
            case UsbManager.ACTION_USB_DEVICE_ATTACHED:
                UsbDevice attachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (attachedDevice != null) {
                    Log.i(TAG, "USB device attached: " + attachedDevice.getDeviceName());
                    requestPermission(context, attachedDevice);
                }
                break;

            case UsbManager.ACTION_USB_DEVICE_DETACHED:
                UsbDevice detachedDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (detachedDevice != null) {
                    Log.i(TAG, "USB device detached: " + detachedDevice.getDeviceName());
                    String serial = detachedDevice.getSerialNumber();
                    if (serial != null) {
                        usbDevices.remove(serial);
                    }
                    if (callback != null) {
                        callback.onUsbDeviceDetached(detachedDevice);
                    }
                }
                break;

            case ACTION_USB_PERMISSION:
                UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                
                if (device != null) {
                    if (granted) {
                        Log.i(TAG, "USB permission granted for: " + device.getDeviceName());
                        String serial = device.getSerialNumber();
                        if (serial != null) {
                            usbDevices.put(serial, device);
                        }
                        if (callback != null) {
                            callback.onUsbDevicePermissionGranted(device);
                        }
                    } else {
                        Log.w(TAG, "USB permission denied for: " + device.getDeviceName());
                    }
                }
                break;
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private void requestPermission(Context context, UsbDevice device) {
        if (App.usbManager == null) return;
        
        if (!App.usbManager.hasPermission(device)) {
            Intent permissionIntent = new Intent(ACTION_USB_PERMISSION);
            permissionIntent.setPackage(context.getPackageName());
            
            int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S 
                ? PendingIntent.FLAG_MUTABLE 
                : 0;
            
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                context, 
                0, 
                permissionIntent, 
                flags
            );
            
            App.usbManager.requestPermission(device, pendingIntent);
        } else {
            // Already has permission
            String serial = device.getSerialNumber();
            if (serial != null) {
                usbDevices.put(serial, device);
            }
            if (callback != null) {
                callback.onUsbDevicePermissionGranted(device);
            }
        }
    }

    public void updateUsbDevices(Context context) {
        if (App.usbManager == null) return;
        
        usbDevices.clear();
        Map<String, UsbDevice> deviceList = App.usbManager.getDeviceList();
        
        for (Map.Entry<String, UsbDevice> entry : deviceList.entrySet()) {
            UsbDevice device = entry.getValue();
            if (device != null && App.usbManager.hasPermission(device)) {
                String serial = device.getSerialNumber();
                if (serial != null) {
                    usbDevices.put(serial, device);
                    Log.i(TAG, "Found USB device with permission: " + serial);
                }
            } else if (device != null) {
                requestPermission(context, device);
            }
        }
    }

    public ConcurrentHashMap<String, UsbDevice> getUsbDevices() {
        return usbDevices;
    }

    public UsbDevice getDeviceBySerial(String serial) {
        return usbDevices.get(serial);
    }
}