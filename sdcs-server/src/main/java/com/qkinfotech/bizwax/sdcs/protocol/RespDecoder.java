package com.qkinfotech.bizwax.sdcs.protocol;

import com.qkinfotech.bizwax.sdcs.buffer.ByteArrayChain;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class RespDecoder {

    private static final long MAX_BULK_STRING_LENGTH = 512 * 1024 * 1024;
    private static final long MAX_ARRAY_LENGTH = 100000;
    private State state = State.TYPE;
    private long bulkStringLength = -1;
    private int bulkStringBytesRead = 0;
    private ByteArrayChain bulkStringChain;
    private long arrayLength = -1;
    private int arrayElementsRead = 0;
    private List<RedisMessage> arrayElements;
    private List<Long> pendingArrayLengths;
    private List<Integer> pendingElementsRead;
    private List<List<RedisMessage>> pendingArrayElements;
    private byte lineBuffer[] = new byte[256];
    private int lineBufferPos = 0;

    private enum State {
        TYPE,
        LINE,
        BULK_STRING_DATA,
        BULK_STRING_CRLF
    }

    public RedisMessage decode(ByteBuffer buffer) {
        while (buffer.hasRemaining()) {
            byte b = buffer.get();

            switch (state) {
                case TYPE:
                    switch (b) {
                        case '\r':
                        case '\n':
                            break;
                        case '+':
                            state = State.LINE;
                            lineBufferPos = 0;
                            lineBuffer[lineBufferPos++] = b;
                            break;
                        case '-':
                            state = State.LINE;
                            lineBufferPos = 0;
                            lineBuffer[lineBufferPos++] = b;
                            break;
                        case ':':
                            state = State.LINE;
                            lineBufferPos = 0;
                            lineBuffer[lineBufferPos++] = b;
                            break;
                        case '$':
                            state = State.LINE;
                            lineBufferPos = 0;
                            lineBuffer[lineBufferPos++] = b;
                            break;
                        case '*':
                            state = State.LINE;
                            lineBufferPos = 0;
                            lineBuffer[lineBufferPos++] = b;
                            break;
                        default:
                            throw new ProtocolException("Invalid RESP type indicator: " + (char) b);
                    }
                    break;

                case LINE:
                    if (b == '\n') {
                        // lineBuffer[0] = type indicator, content = [1 .. lineBufferPos-2],
                        // last byte in buffer is \r (from \r\n terminator)
                        String content = new String(lineBuffer, 1, lineBufferPos - 2);
                        char typeIndicator = findTypeIndicator();

                        switch (typeIndicator) {
                            case '+':
                                state = State.TYPE;
                                return RedisMessage.simpleString(content);

                            case '-':
                                state = State.TYPE;
                                return RedisMessage.error(content);

                            case ':':
                                state = State.TYPE;
                                return RedisMessage.integer(Long.parseLong(content));

                            case '$':
                                bulkStringLength = Long.parseLong(content);
                                if (bulkStringLength == -1) {
                                    state = State.TYPE;
                                    return RedisMessage.bulkString((byte[]) null);
                                }
                                if (bulkStringLength > MAX_BULK_STRING_LENGTH) {
                                    throw new ProtocolException("Bulk string length " + bulkStringLength +
                                            " exceeds maximum " + MAX_BULK_STRING_LENGTH);
                                }
                                bulkStringChain = new ByteArrayChain();
                                bulkStringBytesRead = 0;
                                state = State.BULK_STRING_DATA;
                                break;

                            case '*':
                                arrayLength = Long.parseLong(content);
                                if (arrayLength == -1) {
                                    state = State.TYPE;
                                    return RedisMessage.array(new ArrayList<>());
                                }
                                if (arrayLength > MAX_ARRAY_LENGTH) {
                                    throw new ProtocolException("Array length " + arrayLength +
                                            " exceeds maximum " + MAX_ARRAY_LENGTH);
                                }
                                if (pendingArrayLengths == null) {
                                    pendingArrayLengths = new ArrayList<>();
                                    pendingElementsRead = new ArrayList<>();
                                    pendingArrayElements = new ArrayList<>();
                                }
                                pendingArrayLengths.add(arrayLength);
                                pendingElementsRead.add(0);
                                pendingArrayElements.add(new ArrayList<>());
                                state = State.TYPE;
                                break;
                        }
                        lineBufferPos = 0;
                    } else {
                        if (lineBufferPos >= lineBuffer.length) {
                            byte[] newBuffer = new byte[lineBuffer.length * 2];
                            System.arraycopy(lineBuffer, 0, newBuffer, 0, lineBuffer.length);
                            lineBuffer = newBuffer;
                        }
                        lineBuffer[lineBufferPos++] = b;
                    }
                    break;

                case BULK_STRING_DATA:
                    bulkStringChain.write(b);
                    bulkStringBytesRead++;
                    if (bulkStringBytesRead == bulkStringLength) {
                        state = State.BULK_STRING_CRLF;
                    }
                    break;

                case BULK_STRING_CRLF:
                    if (b == '\n') {
                        RedisMessage msg = RedisMessage.bulkString(bulkStringChain);
                        bulkStringChain = null;
                        state = State.TYPE;

                        if (pendingArrayLengths != null && !pendingArrayLengths.isEmpty()) {
                            int idx = pendingArrayLengths.size() - 1;
                            pendingArrayElements.get(idx).add(msg);
                            pendingElementsRead.set(idx, pendingElementsRead.get(idx) + 1);

                            while (idx >= 0 && pendingElementsRead.get(idx).intValue() == pendingArrayLengths.get(idx).longValue()) {
                                List<RedisMessage> completed = pendingArrayElements.remove(idx);
                                pendingArrayLengths.remove(idx);
                                pendingElementsRead.remove(idx);
                                msg = RedisMessage.array(completed);
                                idx = pendingArrayLengths.size() - 1;
                                if (idx < 0) {
                                    return msg;
                                }
                                pendingArrayElements.get(idx).add(msg);
                                pendingElementsRead.set(idx, pendingElementsRead.get(idx) + 1);
                            }
                        } else {
                            return msg;
                        }
                    } else if (b != '\r') {
                        throw new ProtocolException("Expected LF after bulk string data");
                    }
                    break;
            }
        }
        return null;
    }

    private char findTypeIndicator() {
        if (lineBufferPos > 0) {
            return (char) lineBuffer[0];
        }
        return 0;
    }

    public void reset() {
        state = State.TYPE;
        bulkStringLength = -1;
        bulkStringBytesRead = 0;
        bulkStringChain = null;
        arrayLength = -1;
        arrayElementsRead = 0;
        arrayElements = null;
        lineBufferPos = 0;
        if (pendingArrayLengths != null) {
            pendingArrayLengths.clear();
            pendingElementsRead.clear();
            pendingArrayElements.clear();
        }
    }

    public static class ProtocolException extends RuntimeException {
        public ProtocolException(String message) {
            super(message);
        }
    }
}
