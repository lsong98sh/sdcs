package com.qkinfotech.bizwax.sdcs.persistence;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;

import java.io.*;
import java.nio.file.*;
import java.util.*;

public class AofLoader {

    public List<List<String>> loadCommands(Path aofPath) throws IOException {
        if (!Files.exists(aofPath)) {
            return List.of();
        }

        List<List<String>> commands = new ArrayList<>();
        byte[] data = Files.readAllBytes(aofPath);
        int offset = 0;

        while (offset < data.length) {
            if (data[offset] != '*') {
                offset++;
                continue;
            }

            List<String> command = parseCommand(data, offset);
            if (command != null && !command.isEmpty()) {
                commands.add(command);
                offset = findNextCommandStart(data, offset);
            } else {
                offset++;
            }
        }

        return commands;
    }

    private List<String> parseCommand(byte[] data, int start) {
        List<String> args = new ArrayList<>();
        int pos = start;

        if (pos >= data.length || data[pos] != '*') return null;
        pos++;

        int lenEnd = findLineEnd(data, pos);
        if (lenEnd < 0) return null;
        int arrayLen = Integer.parseInt(new String(data, pos, lenEnd - pos));
        pos = lenEnd + 2;

        for (int i = 0; i < arrayLen; i++) {
            if (pos >= data.length || data[pos] != '$') return null;
            pos++;

            int strLenEnd = findLineEnd(data, pos);
            if (strLenEnd < 0) return null;
            int strLen = Integer.parseInt(new String(data, pos, strLenEnd - pos));
            pos = strLenEnd + 2;

            if (pos + strLen > data.length) return null;
            args.add(new String(data, pos, strLen));
            pos += strLen + 2;
        }

        return args;
    }

    private int findLineEnd(byte[] data, int start) {
        for (int i = start; i < data.length - 1; i++) {
            if (data[i] == '\r' && data[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private int findNextCommandStart(byte[] data, int current) {
        int pos = current + 1;
        while (pos < data.length) {
            if (data[pos] == '*') {
                int lineEnd = findLineEnd(data, pos + 1);
                if (lineEnd > 0) {
                    return pos;
                }
            }
            pos++;
        }
        return data.length;
    }
}
