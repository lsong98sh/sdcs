package com.qkinfotech.bizwax.sdcs.registry;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegistryClient 测试，使用 H2 内存数据库验证注册、心跳、注销流程。
 * <p>
 * 测试方法：创建 H2 内存数据源 → 启动 RegistryClient → 验证数据表记录 → 停止验证注销。
 */
class RegistryClientTest {

    @Test
    void testRegisterAndHeartbeat() throws Exception {
        // 使用 H2 内存数据库
        String jdbcUrl = "jdbc:h2:mem:registry_test_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

        SDCSConfig config = new SDCSConfig();
        config.setRegistryJdbcUrl(jdbcUrl);
        config.setRegisterHash("0-511");
        config.setRegisterAddr("192.168.1.100:6379");

        RegistryClient client = new RegistryClient(config);
        client.start();

        // 等待首次注册完成
        Thread.sleep(500);

        // 验证 H2 数据库中有记录
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT addr, hash_start, hash_end, status FROM sdcs_routes WHERE addr='192.168.1.100:6379'")) {

            assertTrue(rs.next(), "Route should exist after registration");
            assertEquals("192.168.1.100:6379", rs.getString("addr"));
            assertEquals(0, rs.getInt("hash_start"));
            assertEquals(511, rs.getInt("hash_end"));
            assertEquals(1, rs.getInt("status"), "Status should be 1 (online)");
            assertFalse(rs.next(), "Should have exactly one row");
        }

        client.stop();
        Thread.sleep(200);

        // 停止后 status 应为 0（离线）
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT status FROM sdcs_routes WHERE addr='192.168.1.100:6379'")) {

            assertTrue(rs.next());
            assertEquals(0, rs.getInt("status"), "Status should be 0 (offline) after stop");
        }
    }

    @Test
    void testReRegisterUpdate() throws Exception {
        // 验证同一 addr 重复注册时使用 UPDATE 而非报错
        String jdbcUrl = "jdbc:h2:mem:registry_rereg_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

        SDCSConfig config1 = new SDCSConfig();
        config1.setRegistryJdbcUrl(jdbcUrl);
        config1.setRegisterHash("0-255");
        config1.setRegisterAddr("node1:6379");

        RegistryClient client1 = new RegistryClient(config1);
        client1.start();
        Thread.sleep(300);

        // 同一 addr 再次注册（新的哈希范围）
        SDCSConfig config2 = new SDCSConfig();
        config2.setRegistryJdbcUrl(jdbcUrl);
        config2.setRegisterHash("256-511");
        config2.setRegisterAddr("node1:6379");

        RegistryClient client2 = new RegistryClient(config2);
        client2.start();
        Thread.sleep(300);

        // 验证 hash 范围已更新
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT hash_start, hash_end FROM sdcs_routes WHERE addr='node1:6379'")) {

            assertTrue(rs.next(), "Route should exist");
            assertEquals(256, rs.getInt("hash_start"));
            assertEquals(511, rs.getInt("hash_end"));
            assertFalse(rs.next(), "Should have only one row");
        }

        client1.stop();
        client2.stop();
    }

    @Test
    void testSingleHashValue() throws Exception {
        // 单个 hash 值 (不是范围)
        String jdbcUrl = "jdbc:h2:mem:registry_single_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

        SDCSConfig config = new SDCSConfig();
        config.setRegistryJdbcUrl(jdbcUrl);
        config.setRegisterHash("100");
        config.setRegisterAddr("single:6379");

        RegistryClient client = new RegistryClient(config);
        client.start();
        Thread.sleep(300);

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT hash_start, hash_end FROM sdcs_routes WHERE addr='single:6379'")) {

            assertTrue(rs.next());
            assertEquals(100, rs.getInt("hash_start"));
            assertEquals(100, rs.getInt("hash_end"));
        }

        client.stop();
    }

    @Test
    void testWithoutJdbcUrl() {
        // 未配置 JDBC URL 时应直接跳过（不抛异常）
        SDCSConfig config = new SDCSConfig();
        // 不设置 registryJdbcUrl
        config.setRegisterHash("0-511");
        config.setRegisterAddr("skip:6379");

        RegistryClient client = new RegistryClient(config);
        // 不应抛异常
        client.start();
        client.stop();
    }

    @Test
    void testStopNotStarted() {
        // 从未 start 的 client 调用 stop 不应抛异常
        SDCSConfig config = new SDCSConfig();
        RegistryClient client = new RegistryClient(config);
        client.stop();
    }

    @Test
    void testMultipleRegistries() throws Exception {
        // 多个不同的 addr 分别注册
        String jdbcUrl = "jdbc:h2:mem:registry_multi_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1";

        SDCSConfig config1 = new SDCSConfig();
        config1.setRegistryJdbcUrl(jdbcUrl);
        config1.setRegisterHash("0-255");
        config1.setRegisterAddr("node1:6379");

        SDCSConfig config2 = new SDCSConfig();
        config2.setRegistryJdbcUrl(jdbcUrl);
        config2.setRegisterHash("256-511");
        config2.setRegisterAddr("node2:6379");

        RegistryClient client1 = new RegistryClient(config1);
        client1.start();
        Thread.sleep(200);

        RegistryClient client2 = new RegistryClient(config2);
        client2.start();
        Thread.sleep(200);

        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT addr, hash_start, hash_end FROM sdcs_routes ORDER BY addr")) {

            assertTrue(rs.next());
            assertEquals("node1:6379", rs.getString("addr"));
            assertEquals(0, rs.getInt("hash_start"));

            assertTrue(rs.next());
            assertEquals("node2:6379", rs.getString("addr"));
            assertEquals(256, rs.getInt("hash_start"));
        }

        client1.stop();
        client2.stop();
    }
}
