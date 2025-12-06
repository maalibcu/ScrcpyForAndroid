package org.client.scrcpy;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;

import org.client.scrcpy.App;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class UsbChannel implements AdbChannel {
    private final UsbDeviceConnection usbConnection;
    private UsbInterface usbInterface = null;
    private UsbEndpoint endpointIn = null;
    private UsbEndpoint endpointOut = null;
    private final BlockingQueue<ByteBuffer> sourceBuffer = new LinkedBlockingQueue<>();
    private final Thread readBackgroundThread = new Thread(this::readBackground);
    private final LinkedList<UsbRequest> mInRequestPool = new LinkedList<>();
    private volatile boolean closed = false;

    public UsbChannel(UsbDevice usbDevice) throws IOException {
        // Get USB manager from App context
        UsbManager usbManager = (UsbManager) App.mContext.getSystemService(App.mContext.USB_SERVICE);
        if (usbManager == null) throw new IOException("USB Manager not available");
        
        // Connect to USB device
        usbConnection = usbManager.openDevice(usbDevice);
        if (usbConnection == null) throw new IOException("Cannot open USB device");
        
        // Find ADB interface
        for (int i = 0; i < usbDevice.getInterfaceCount(); i++) {
            UsbInterface tmpUsbInterface = usbDevice.getInterface(i);
            if ((tmpUsbInterface.getInterfaceClass() == UsbConstants.USB_CLASS_VENDOR_SPEC) 
                && (tmpUsbInterface.getInterfaceSubclass() == 66) 
                && (tmpUsbInterface.getInterfaceProtocol() == 1)) {
                usbInterface = tmpUsbInterface;
                break;
            }
        }
        
        if (usbInterface == null) {
            usbConnection.close();
            throw new IOException("ADB interface not found");
        }
        
        // Claim interface
        if (!usbConnection.claimInterface(usbInterface, true)) {
            usbConnection.close();
            throw new IOException("Cannot claim USB interface");
        }
        
        // Find input/output endpoints
        for (int i = 0; i < usbInterface.getEndpointCount(); i++) {
            UsbEndpoint endpoint = usbInterface.getEndpoint(i);
            if (endpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (endpoint.getDirection() == UsbConstants.USB_DIR_OUT) {
                    endpointOut = endpoint;
                } else if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) {
                    endpointIn = endpoint;
                }
            }
        }
        
        if (endpointIn == null || endpointOut == null) {
            usbConnection.releaseInterface(usbInterface);
            usbConnection.close();
            throw new IOException("ADB endpoints not found");
        }
        
        readBackgroundThread.start();
    }

    @Override
    public void write(ByteBuffer data) throws IOException {
        if (closed) throw new IOException("Channel is closed");
        
        // ADB over USB requires header and payload to be sent separately
        while (data.remaining() > 0) {
            // Read header
            byte[] header = new byte[AdbProtocol.ADB_HEADER_LENGTH];
            data.get(header);
            int sent = usbConnection.bulkTransfer(endpointOut, header, header.length, 1000);
            if (sent < 0) throw new IOException("USB write failed");
            
            // Read payload
            int payloadLength = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(12);
            if (payloadLength > 0) {
                byte[] payload = new byte[payloadLength];
                data.get(payload);
                sent = usbConnection.bulkTransfer(endpointOut, payload, payload.length, 1000);
                if (sent < 0) throw new IOException("USB write failed");
            }
        }
    }

    @Override
    public ByteBuffer read(int size) throws InterruptedException, IOException {
        if (closed) throw new IOException("Channel is closed");
        
        ByteBuffer result = ByteBuffer.allocate(size);
        while (result.remaining() > 0 && !closed) {
            ByteBuffer chunk = sourceBuffer.take();
            if (chunk.remaining() == 0) throw new IOException("Channel closed");
            
            int toRead = Math.min(result.remaining(), chunk.remaining());
            byte[] temp = new byte[toRead];
            chunk.get(temp);
            result.put(temp);
            
            if (chunk.remaining() > 0) {
                // Put back remaining data
                sourceBuffer.add(chunk);
            }
        }
        
        result.flip();
        return result;
    }

    private void readBackground() {
        try {
            while (!Thread.interrupted() && !closed) {
                // Read header
                ByteBuffer header = readRequest(AdbProtocol.ADB_HEADER_LENGTH);
                if (header == null || header.remaining() < AdbProtocol.ADB_HEADER_LENGTH) {
                    throw new IOException("Failed to read header");
                }
                header.order(ByteOrder.LITTLE_ENDIAN);
                sourceBuffer.add(header);
                
                // Read payload
                int payloadLength = header.getInt(12);
                if (payloadLength > 0) {
                    ByteBuffer payload = readRequest(payloadLength);
                    if (payload == null) throw new IOException("Failed to read payload");
                    sourceBuffer.add(payload);
                }
            }
        } catch (Exception e) {
            close();
        }
    }

    private ByteBuffer readRequest(int len) throws IOException {
        // Get or create USB request
        UsbRequest request;
        synchronized (mInRequestPool) {
            if (mInRequestPool.isEmpty()) {
                request = new UsbRequest();
                request.initialize(usbConnection, endpointIn);
            } else {
                request = mInRequestPool.removeFirst();
            }
        }
        
        ByteBuffer data = ByteBuffer.allocate(len);
        request.setClientData(data);
        
        // Queue async request
        if (!request.queue(data, len)) {
            synchronized (mInRequestPool) {
                mInRequestPool.add(request);
            }
            throw new IOException("Failed to queue USB request");
        }
        
        // Wait for response
        while (!closed) {
            UsbRequest wait = usbConnection.requestWait();
            if (wait == null) throw new IOException("requestWait returned null");
            
            if (wait.getEndpoint() == endpointIn) {
                ByteBuffer clientData = (ByteBuffer) wait.getClientData();
                synchronized (mInRequestPool) {
                    mInRequestPool.add(request);
                }
                
                if (clientData == data) {
                    data.flip();
                    return data;
                }
            }
        }
        
        return null;
    }

    @Override
    public void flush() {
        // No-op for USB
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        
        readBackgroundThread.interrupt();
        
        try {
            // Force error to disconnect USB
            if (endpointOut != null) {
                usbConnection.bulkTransfer(endpointOut, new byte[100], 100, 100);
            }
        } catch (Exception ignored) {}
        
        try {
            if (usbInterface != null) {
                usbConnection.releaseInterface(usbInterface);
            }
            usbConnection.close();
        } catch (Exception ignored) {}
        
        // Signal close to waiting threads
        sourceBuffer.add(ByteBuffer.allocate(0));
    }
}