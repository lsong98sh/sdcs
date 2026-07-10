package com.qkinfotech.bizwax.sdcs.protocol;

import com.qkinfotech.bizwax.sdcs.buffer.ByteArrayChain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

public class RespEncoder {

    private final ByteBuffer buffer;

    public RespEncoder(int bufferSize) {
        this.buffer = ByteBuffer.allocate(bufferSize);
    }

    public void encode(RedisMessage msg, ByteBuffer output) {
        switch (msg.getType()) {
            case SIMPLE_STRING:
                output.put((byte) '+');
                output.put(msg.getData());
                output.put((byte) '\r');
                output.put((byte) '\n');
                break;

            case ERROR:
                output.put((byte) '-');
                output.put(msg.getData());
                output.put((byte) '\r');
                output.put((byte) '\n');
                break;

            case INTEGER:
                output.put((byte) ':');
                byte[] intBytes = String.valueOf(msg.getIntegerValue()).getBytes(StandardCharsets.US_ASCII);
                output.put(intBytes);
                output.put((byte) '\r');
                output.put((byte) '\n');
                break;

            case BULK_STRING:
                ByteArrayChain chain = msg.getChain();
                if (chain != null) {
                    // Zero-copy: write chain chunks directly
                    long len = chain.length();
                    output.put((byte) '$');
                    output.put(String.valueOf(len).getBytes(StandardCharsets.US_ASCII));
                    output.put((byte) '\r');
                    output.put((byte) '\n');
                    chain.forEach((chunk, off, chunkLen) -> output.put(chunk, off, chunkLen));
                    output.put((byte) '\r');
                    output.put((byte) '\n');
                } else {
                    byte[] data = msg.getData();
                    if (data == null) {
                        output.put((byte) '$');
                        output.put((byte) '-');
                        output.put((byte) '1');
                        output.put((byte) '\r');
                        output.put((byte) '\n');
                    } else {
                        output.put((byte) '$');
                        byte[] lenBytes = String.valueOf(data.length).getBytes(StandardCharsets.US_ASCII);
                        output.put(lenBytes);
                        output.put((byte) '\r');
                        output.put((byte) '\n');
                        output.put(data);
                        output.put((byte) '\r');
                        output.put((byte) '\n');
                    }
                }
                break;

            case ARRAY:
                var elements = msg.getElements();
                if (elements == null) {
                    output.put((byte) '*');
                    output.put((byte) '-');
                    output.put((byte) '1');
                    output.put((byte) '\r');
                    output.put((byte) '\n');
                } else {
                    output.put((byte) '*');
                    byte[] lenBytes = String.valueOf(elements.size()).getBytes(StandardCharsets.US_ASCII);
                    output.put(lenBytes);
                    output.put((byte) '\r');
                    output.put((byte) '\n');
                    for (RedisMessage element : elements) {
                        encode(element, output);
                    }
                }
                break;
        }
    }

    public void reset() {
        buffer.clear();
    }
}
