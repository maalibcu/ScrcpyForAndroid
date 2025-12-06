package org.client.scrcpy.buffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class BufferStream {
    private final BlockingQueue<ByteBuffer> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed = false;
    private final UnderlySocketFunction socketFunction;
    private volatile boolean canWrite = false;

    public interface UnderlySocketFunction {
        void connect(BufferStream bufferStream) throws Exception;
        void write(BufferStream bufferStream, ByteBuffer buffer) throws Exception;
        void flush(BufferStream bufferStream) throws Exception;
        void close(BufferStream bufferStream);
    }

    public BufferStream(boolean autoConnect, boolean canMultipleSend, 
                       UnderlySocketFunction socketFunction) throws Exception {
        this.socketFunction = socketFunction;
        if (autoConnect) {
            socketFunction.connect(this);
        }
    }

    public void write(ByteBuffer buffer) throws IOException {
        if (closed) throw new IOException("Stream is closed");
        try {
            socketFunction.write(this, buffer);
        } catch (Exception e) {
            throw new IOException("Write failed", e);
        }
    }

    public void pushSource(ByteBuffer buffer) {
        queue.add(buffer);
    }

    public ByteBuffer read(int size) throws InterruptedException {
        ByteBuffer result = ByteBuffer.allocate(size);
        while (result.remaining() > 0 && !closed) {
            ByteBuffer chunk = queue.take();
            if (chunk.remaining() == 0) break;
            
            int toRead = Math.min(result.remaining(), chunk.remaining());
            byte[] temp = new byte[toRead];
            chunk.get(temp);
            result.put(temp);
            
            if (chunk.remaining() > 0) {
                queue.add(chunk);
            }
        }
        result.flip();
        return result;
    }

    public void setCanWrite(boolean canWrite) {
        this.canWrite = canWrite;
    }

    public boolean isClosed() {
        return closed;
    }

    public void close() {
        if (closed) return;
        closed = true;
        socketFunction.close(this);
        queue.add(ByteBuffer.allocate(0)); // Signal close
    }

    public ByteBuffer readByteArrayBeforeClose() {
        ByteBuffer result = ByteBuffer.allocate(8192);
        try {
            while (!closed) {
                ByteBuffer chunk = queue.poll();
                if (chunk == null || chunk.remaining() == 0) break;
                result.put(chunk);
            }
        } catch (Exception ignored) {}
        result.flip();
        return result;
    }
}