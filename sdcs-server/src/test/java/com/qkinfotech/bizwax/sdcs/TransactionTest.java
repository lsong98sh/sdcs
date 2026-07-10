package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.command.CommandDispatcher;
import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.transaction.TransactionManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TransactionTest {

    private DatabaseManager dbManager;
    private CommandDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        TransactionManager.cleanup();
        dbManager = new DatabaseManager();
        dispatcher = new CommandDispatcher(dbManager);
    }

    private static RedisMessage bulkString(String s) {
        return RedisMessage.bulkString(s.getBytes());
    }

    private static List<RedisMessage> args(String... strings) {
        return List.of(strings).stream().map(s -> RedisMessage.bulkString(s.getBytes())).toList();
    }

    @Test
    void testMultiThenExec() {
        dispatcher.dispatch("MULTI", List.of());
        dispatcher.dispatch("SET", args("txkey1", "txval1"));
        RedisMessage execResult = dispatcher.dispatch("EXEC", List.of());
        assertNotNull(execResult);
        assertFalse(TransactionManager.isInTransaction());
        RedisMessage getResult = dispatcher.dispatch("GET", args("txkey1"));
        assertEquals("txval1", getResult.asString());
    }

    @Test
    void testMultiThenDiscard() {
        dispatcher.dispatch("SET", args("txkey2", "original"));
        dispatcher.dispatch("MULTI", List.of());
        dispatcher.dispatch("SET", args("txkey2", "discarded"));
        dispatcher.dispatch("DISCARD", List.of());
        assertFalse(TransactionManager.isInTransaction());
        RedisMessage getResult = dispatcher.dispatch("GET", args("txkey2"));
        assertEquals("original", getResult.asString());
    }

    @Test
    void testWatchThenExec() {
        dispatcher.dispatch("SET", args("wkey", "wval"));
        dispatcher.dispatch("WATCH", args("wkey"));
        dispatcher.dispatch("MULTI", List.of());
        dispatcher.dispatch("SET", args("wkey", "newwval"));
        TransactionManager.markDirty("wkey");
        RedisMessage execResult = dispatcher.dispatch("EXEC", List.of());
        assertNotNull(execResult);
        RedisMessage getResult = dispatcher.dispatch("GET", args("wkey"));
        assertEquals("wval", getResult.asString());
    }

    @Test
    void testTransactionCleanup() {
        TransactionManager.multi();
        assertTrue(TransactionManager.isInTransaction());
        TransactionManager.cleanup();
        assertFalse(TransactionManager.isInTransaction());
    }

    @Test
    void testMultiCantNest() {
        dispatcher.dispatch("MULTI", List.of());
        dispatcher.dispatch("SET", args("nested", "val"));
        RedisMessage result = dispatcher.dispatch("MULTI", List.of());
        assertNotNull(result);
        assertTrue(result.asString().contains("MULTI calls can not be nested") ||
                result.asString().contains("ERR"));
        dispatcher.dispatch("DISCARD", List.of());
    }

    @Test
    void testExecWithoutMulti() {
        RedisMessage result = dispatcher.dispatch("EXEC", List.of());
        assertEquals(RedisMessage.Type.ERROR, result.getType());
        assertTrue(result.asString().contains("EXEC without MULTI"));
    }

    @Test
    void testDiscardWithoutMulti() {
        RedisMessage result = dispatcher.dispatch("DISCARD", List.of());
        assertEquals(RedisMessage.Type.ERROR, result.getType());
        assertTrue(result.asString().contains("DISCARD without MULTI"));
    }

    @Test
    void testQueueCommandWithoutMulti() {
        dispatcher.dispatch("SET", args("qkey", "qval"));
        RedisMessage result = dispatcher.dispatch("GET", args("qkey"));
        assertEquals(RedisMessage.Type.BULK_STRING, result.getType());
        assertEquals("qval", result.asString());
    }

    @Test
    void testWatchThenMarkDirtyThenExec() {
        dispatcher.dispatch("SET", args("wmdkey", "initial"));
        dispatcher.dispatch("WATCH", args("wmdkey"));
        TransactionManager.markDirty("wmdkey");
        dispatcher.dispatch("MULTI", List.of());
        dispatcher.dispatch("SET", args("wmdkey", "txval"));
        RedisMessage execResult = dispatcher.dispatch("EXEC", List.of());
        assertNotNull(execResult);
        assertEquals(RedisMessage.Type.ARRAY, execResult.getType());
        assertTrue(execResult.getElements() == null || execResult.getElements().isEmpty());
        RedisMessage getResult = dispatcher.dispatch("GET", args("wmdkey"));
        assertEquals("initial", getResult.asString());
    }

    @Test
    void testWatchUnrelatedKey() {
        dispatcher.dispatch("SET", args("keyA", "valA"));
        dispatcher.dispatch("SET", args("keyB", "valB"));
        dispatcher.dispatch("WATCH", args("keyA"));
        TransactionManager.markDirty("keyB");
        dispatcher.dispatch("MULTI", List.of());
        dispatcher.dispatch("SET", args("keyC", "valC"));
        RedisMessage execResult = dispatcher.dispatch("EXEC", List.of());
        assertNotNull(execResult);
        assertEquals(1, execResult.getElements().size());
        assertEquals("OK", execResult.getElements().get(0).asString());
        RedisMessage getResult = dispatcher.dispatch("GET", args("keyC"));
        assertEquals("valC", getResult.asString());
    }

    @Test
    void testMultiThreadTransaction() throws Exception {
        dispatcher.dispatch("SET", args("threadkey1", "initial1"));
        dispatcher.dispatch("SET", args("threadkey2", "initial2"));

        Thread t1 = new Thread(() -> {
            TransactionManager.cleanup();
            CommandDispatcher disp = new CommandDispatcher(new DatabaseManager());
            disp.dispatch("MULTI", List.of());
            disp.dispatch("SET", args("threadkey1", "t1val"));
            RedisMessage r = disp.dispatch("EXEC", List.of());
            assertNotNull(r);
        });

        Thread t2 = new Thread(() -> {
            TransactionManager.cleanup();
            CommandDispatcher disp = new CommandDispatcher(new DatabaseManager());
            disp.dispatch("MULTI", List.of());
            disp.dispatch("SET", args("threadkey2", "t2val"));
            RedisMessage r = disp.dispatch("EXEC", List.of());
            assertNotNull(r);
        });

        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertFalse(TransactionManager.isInTransaction());
        dispatcher.dispatch("GET", args("threadkey1"));
        dispatcher.dispatch("GET", args("threadkey2"));
    }
}
