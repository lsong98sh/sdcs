package com.qkinfotech.bizwax.sdcs.persistence;

public class RdbConstants {

    public static final byte[] MAGIC = "REDIS".getBytes();
    public static final String VERSION = "0009";

    public static final int OP_SELECTDB = 0xFE;
    public static final int OP_EXPIRETIME_MS = 0xFC;
    public static final int OP_EXPIRETIME = 0xFD;
    public static final int OP_EOF = 0xFF;
    public static final int OP_RESIZEDB = 0xFB;
    public static final int OP_AUX = 0xFA;

    private RdbConstants() {}
}
