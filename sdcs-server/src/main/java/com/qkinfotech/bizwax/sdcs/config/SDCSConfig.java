package com.qkinfotech.bizwax.sdcs.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SDCS 服务器全局配置。
 * <p>
 * 采用单例模式，通过 {@link #getInstance()} 获取实例。
 * 支持从命令行参数解析配置（参见 {@link #parseArgs(String[])}），
 * 也支持运行时通过 CONFIG SET 命令动态修改部分配置项。
 * </p>
 *
 * <h3>三种持久化模式</h3>
 * <pre>
 *   --persistence rdb_aof    默认模式，RDB 快照 + AOF 追加日志
 *   --persistence rocksdb    基于 RocksDB 的嵌入式持久化
 *   --persistence none       纯内存模式，无持久化
 * </pre>
 */
public class SDCSConfig {

    /**
     * 持久化模式枚举，决定服务器启动时采用何种数据持久化策略。
     */
    public enum PersistenceType {
        /** 纯内存模式，不进行任何持久化，重启后数据丢失 */
        NONE,
        /** RDB 快照 + AOF 追加日志模式（默认），兼容 Redis 的传统持久化方案 */
        RDB_AOF,
        /** RocksDB 嵌入式 LSM-Tree 持久化模式，数据异步批量写入，支持热缓存加速读 */
        ROCKSDB
    }

    /** 单例实例 */
    private static SDCSConfig instance;

    /** 监听端口号，默认 6379（与 Redis 默认端口一致） */
    private int port = 6379;

    /** 最大并发连接数，默认 10000 */
    private int maxConnections = 10000;

    /**
     * 客户端空闲超时秒数。
     * <ul>
     *   <li>0 或负数表示永不超时</li>
     *   <li>正值表示 N 秒无读请求后断开连接</li>
     * </ul>
     */
    private int connectionTimeout = 0;

    /** TCP 全连接队列大小，默认 1000 */
    private int acceptCount = 1000;

    /** 绑定地址，默认 0.0.0.0（监听所有网络接口） */
    private String bindAddress = "0.0.0.0";

    /** RDB 持久化文件名（仅 RDB_AOF 模式有效），默认 sdcs.rdb */
    private String dbFilename = "sdcs.rdb";

    /** AOF 持久化文件名（仅 RDB_AOF 模式有效），默认 sdcs.aof */
    private String aofFilename = "sdcs.aof";

    /** 是否启用 AOF（仅 RDB_AOF 模式），默认 false */
    private boolean aofEnabled = false;

    /** 逻辑数据库数量，默认 16（对应 Redis 的 SELECT 0~15） */
    private int databases = 16;

    /** Prometheus 指标暴露端口，默认 9121 */
    private int metricsPort = 9121;

    /** 是否启用 Metrics 采集和 Prometheus 导出，默认 true */
    private boolean metricsEnabled = true;

    /** AUTH 认证密码，null 表示不开启认证 */
    private String requirepass = null;

    /** 主从复制目标，格式 "host port"，null 表示不启用从节点模式 */
    private String replicaof = null;

    /** 注册表数据库 JDBC URL，非空时启用注册 */
    private String registryJdbcUrl = null;

    /** 注册表数据库用户名 */
    private String registryUsername = null;

    /** 注册表数据库密码 */
    private String registryPassword = null;

    /** 本节点负责的 slot 范围，如 "0-511" */
    private String registerHash = null;

    /** 本节点对外地址，如 "192.168.1.10:6379" */
    private String registerAddr = null;

    /** 是否仅打印帮助信息（--help 时设置） */
    private boolean helpRequested = false;

    /** 持久化模式，默认 RDB_AOF */
    private PersistenceType persistenceType = PersistenceType.RDB_AOF;

    /**
     * RocksDB 模式下热缓存的最大 Key 数量。
     * 超过该数量时按 LRU 淘汰缓存，仅在热缓存中失效时回查 RocksDB。
     * 默认 100000 条。
     */
    private int hotCacheSize = 100000;

    /** 持久化数据目录（存放 RDB/AOF/RocksDB 数据文件），默认 "data" */
    private String dataDir = "data";

    /**
     * 构造配置实例并注册为全局单例。
     * <p>
     * 在服务器启动入口 {@code SDCSServer.main()} 中优先调用此构造器，
     * 后续通过 {@link #getInstance()} 访问。
     * </p>
     */
    public SDCSConfig() {
        instance = this;
    }

    /**
     * 获取全局配置单例实例。
     *
     * @return SDCSConfig 实例，若尚未初始化则返回 null
     */
    public static SDCSConfig getInstance() {
        return instance;
    }

    /**
     * 按名称查询配置项（对应 Redis CONFIG GET 命令）。
     * <p>
     * 支持查询的配置项：requirepass, port, bind, databases, maxclients,
     * timeout, accept-count, aof, dbfilename, aoffilename, metrics-port,
     * metrics, replicaof, persistence, data-dir, hot-cache-size。
     * </p>
     *
     * @param key 配置项名称
     * @return 配置值字符串，未知配置项返回 null
     */
    public String getConfig(String key) {
        return switch (key) {
            case "requirepass" -> requirepass == null ? null : "****";
            case "port" -> String.valueOf(port);
            case "bind" -> bindAddress;
            case "databases" -> String.valueOf(databases);
            case "maxclients" -> String.valueOf(maxConnections);
            case "timeout" -> String.valueOf(connectionTimeout);
            case "accept-count" -> String.valueOf(acceptCount);
            case "aof" -> aofEnabled ? "yes" : "no";
            case "dbfilename" -> dbFilename;
            case "aoffilename" -> aofFilename;
            case "metrics-port" -> String.valueOf(metricsPort);
            case "metrics" -> metricsEnabled ? "yes" : "no";
            case "replicaof" -> replicaof;
            case "persistence" -> persistenceType.name().toLowerCase();
            case "data-dir" -> dataDir;
            case "hot-cache-size" -> String.valueOf(hotCacheSize);
            default -> null;
        };
    }

    /**
     * 动态设置运行时配置项（对应 Redis CONFIG SET 命令）。
     * <p>
     * 当前支持运行时修改的配置项：requirepass, persistence, data-dir, hot-cache-size。
     * 其余配置项需重启服务器生效。
     * </p>
     *
     * @param key   配置项名称
     * @param value 配置值
     * @return true 表示设置成功，false 表示配置项不支持运行时修改或未知
     */
    public boolean setConfig(String key, String value) {
        switch (key) {
            case "requirepass":
                requirepass = value;
                return true;
            case "persistence":
                persistenceType = PersistenceType.valueOf(value.toUpperCase());
                return true;
            case "data-dir":
                dataDir = value;
                return true;
            case "hot-cache-size":
                hotCacheSize = Integer.parseInt(value);
                return true;
        }
        return false;
    }

    /**
     * 获取所有配置项的键值对，用于 CONFIG GET * 命令或状态展示。
     *
     * @return 有序的配置项键值 Map
     */
    public Map<String, String> getAllConfigs() {
        Map<String, String> configs = new LinkedHashMap<>();
        configs.put("requirepass", requirepass);
        configs.put("port", String.valueOf(port));
        configs.put("bind", bindAddress);
        configs.put("databases", String.valueOf(databases));
        configs.put("maxclients", String.valueOf(maxConnections));
        configs.put("timeout", String.valueOf(connectionTimeout));
        configs.put("accept-count", String.valueOf(acceptCount));
        configs.put("aof", aofEnabled ? "yes" : "no");
        configs.put("dbfilename", dbFilename);
        configs.put("aoffilename", aofFilename);
        configs.put("metrics-port", String.valueOf(metricsPort));
        configs.put("metrics", metricsEnabled ? "yes" : "no");
        configs.put("replicaof", replicaof);
        configs.put("persistence", persistenceType.name().toLowerCase());
        configs.put("data-dir", dataDir);
        configs.put("hot-cache-size", String.valueOf(hotCacheSize));
        return configs;
    }

    public boolean isHelpRequested() {
        return helpRequested;
    }

    public static void printHelp() {
        System.out.println("SDCS - Simple Distributed Cache Server");
        System.out.println();
        System.out.println("Usage: java com.qkinfotech.bizwax.sdcs.SDCSServer [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --help                    Print this help message");
        System.out.println("  --port <port>             Listen port (default: 6379)");
        System.out.println("  --bind <address>          Bind address (default: 0.0.0.0)");
        System.out.println("  --timeout <seconds>       Client idle timeout, 0=never (default: 0)");
        System.out.println("  --maxclients <num>        Max concurrent connections (default: 10000)");
        System.out.println("  --requirepass <password>  AUTH password (default: none)");
        System.out.println();
        System.out.println("Persistence (choose one mode):");
        System.out.println("  --persistence <mode>      Persistence mode: none / rdb_aof / rocksdb");
        System.out.println("                              none    - in-memory only, no persistence");
        System.out.println("                              rdb_aof - RDB snapshots (optional + AOF)");
        System.out.println("                              rocksdb - embedded RocksDB (default: rdb_aof)");
        System.out.println("  --aof                     Enable AOF append-log (only in rdb_aof mode)");
        System.out.println("  --data-dir <path>         Data directory (default: data)");
        System.out.println("  --dbfilename <name>       RDB file name (default: sdcs.rdb)");
        System.out.println("  --aoffilename <name>      AOF file name (default: sdcs.aof)");
        System.out.println("  --hot-cache-size <num>    RocksDB hot cache size (default: 100000)");
        System.out.println();
        System.out.println("Metrics:");
        System.out.println("  --metrics-port <port>     Prometheus metrics port (default: 9121)");
        System.out.println("  --no-metrics              Disable metrics collection");
        System.out.println();
        System.out.println("Replication (resolved at proxy layer):");
        System.out.println("  --replicaof <host> <port> Replicate from master (default: none)");
        System.out.println();
        System.out.println("Registry / Cluster:");
        System.out.println("  --registry-jdbc-url <url> Registry database JDBC URL");
        System.out.println("  --registry-username <user> Registry database username");
        System.out.println("  --registry-password <pwd>  Registry database password");
        System.out.println("  --register-hash <range>    Slot range, e.g. 0-511");
        System.out.println("  --register-addr <addr>     Node external address, e.g. 192.168.1.10:6379");
    }

    public String getRequirepass() {
        return requirepass;
    }

    public void setRequirepass(String requirepass) {
        this.requirepass = requirepass;
    }

    public int getMetricsPort() {
        return metricsPort;
    }

    public void setMetricsPort(int metricsPort) {
        this.metricsPort = metricsPort;
    }

    public boolean isMetricsEnabled() {
        return metricsEnabled;
    }

    public void setMetricsEnabled(boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public int getMaxConnections() {
        return maxConnections;
    }

    public void setMaxConnections(int maxConnections) {
        this.maxConnections = maxConnections;
    }

    public int getConnectionTimeout() {
        return connectionTimeout;
    }

    public void setConnectionTimeout(int connectionTimeout) {
        this.connectionTimeout = connectionTimeout;
    }

    public int getAcceptCount() {
        return acceptCount;
    }

    public void setAcceptCount(int acceptCount) {
        this.acceptCount = acceptCount;
    }

    public String getBindAddress() {
        return bindAddress;
    }

    public void setBindAddress(String bindAddress) {
        this.bindAddress = bindAddress;
    }

    public String getDbFilename() {
        return dbFilename;
    }

    public void setDbFilename(String dbFilename) {
        this.dbFilename = dbFilename;
    }

    public String getAofFilename() {
        return aofFilename;
    }

    public void setAofFilename(String aofFilename) {
        this.aofFilename = aofFilename;
    }

    public boolean isAofEnabled() {
        return aofEnabled;
    }

    public void setAofEnabled(boolean aofEnabled) {
        this.aofEnabled = aofEnabled;
    }

    public int getDatabases() {
        return databases;
    }

    public void setDatabases(int databases) {
        this.databases = databases;
    }

    public String getReplicaof() {
        return replicaof;
    }

    public void setReplicaof(String replicaof) {
        this.replicaof = replicaof;
    }

    public String getRegistryJdbcUrl() { return registryJdbcUrl; }
    public void setRegistryJdbcUrl(String url) { this.registryJdbcUrl = url; }
    public String getRegistryUsername() { return registryUsername; }
    public void setRegistryUsername(String user) { this.registryUsername = user; }
    public String getRegistryPassword() { return registryPassword; }
    public void setRegistryPassword(String pwd) { this.registryPassword = pwd; }
    public String getRegisterHash() { return registerHash; }
    public void setRegisterHash(String hash) { this.registerHash = hash; }
    public String getRegisterAddr() { return registerAddr; }
    public void setRegisterAddr(String addr) { this.registerAddr = addr; }

    public PersistenceType getPersistenceType() {
        return persistenceType;
    }

    public void setPersistenceType(PersistenceType persistenceType) {
        this.persistenceType = persistenceType;
    }

    public int getHotCacheSize() {
        return hotCacheSize;
    }

    public void setHotCacheSize(int hotCacheSize) {
        this.hotCacheSize = hotCacheSize;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    /**
     * 从命令行参数解析配置。
     * <p>
     * 支持的命令行参数列表：
     * <pre>
     *   --port &lt;port&gt;              监听端口（默认 6379）
     *   --timeout &lt;seconds&gt;        客户端空闲超时（默认 0=永不超时）
     *   --maxclients &lt;num&gt;         最大客户端连接数（默认 10000）
     *   --bind &lt;address&gt;           绑定地址（默认 0.0.0.0）
     *   --aof                       启用 AOF 追加日志（仅 RDB_AOF 模式）
     *   --metrics-port &lt;port&gt;      Prometheus 指标端口（默认 9121）
     *   --no-metrics                禁用 Metrics 采集
     *   --requirepass &lt;password&gt;   AUTH 认证密码
     *   --replicaof &lt;host&gt; &lt;port&gt;  主从复制：成为指定主节点的从节点
     *   --persistence &lt;mode&gt;       持久化模式：none / rdb_aof / rocksdb（默认 rdb_aof）
     *   --data-dir &lt;path&gt;          持久化数据目录（默认 data）
     *   --hot-cache-size &lt;num&gt;     RocksDB 模式热缓存大小（默认 100000）
     * </pre>
     * 参数不区分先后顺序，--port 和 --persistence 等带值的参数会消费后续一个或多个 token。
     * </p>
     *
     * @param args 命令行参数字符串数组，通常直接传入 {@code main(String[] args)}
     */
    public void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help":
                    printHelp();
                    helpRequested = true;
                    return;
                case "--port":
                    if (i + 1 < args.length) {
                        port = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--timeout":
                    if (i + 1 < args.length) {
                        connectionTimeout = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--maxclients":
                    if (i + 1 < args.length) {
                        maxConnections = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--bind":
                    if (i + 1 < args.length) {
                        bindAddress = args[++i];
                    }
                    break;
                case "--aof":
                    aofEnabled = true;
                    persistenceType = PersistenceType.RDB_AOF;
                    break;
                case "--metrics-port":
                    if (i + 1 < args.length) {
                        metricsPort = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--no-metrics":
                    metricsEnabled = false;
                    break;
                case "--requirepass":
                    if (i + 1 < args.length) {
                        requirepass = args[++i];
                    }
                    break;
                case "--replicaof":
                    if (i + 2 < args.length) {
                        replicaof = args[++i] + " " + args[++i];
                    }
                    break;
                case "--persistence":
                    if (i + 1 < args.length) {
                        persistenceType = PersistenceType.valueOf(args[++i].toUpperCase());
                    }
                    break;
                case "--data-dir":
                    if (i + 1 < args.length) {
                        dataDir = args[++i];
                    }
                    break;
                case "--hot-cache-size":
                    if (i + 1 < args.length) {
                        hotCacheSize = Integer.parseInt(args[++i]);
                    }
                    break;
                case "--registry-jdbc-url":
                    if (i + 1 < args.length) registryJdbcUrl = args[++i];
                    break;
                case "--registry-username":
                    if (i + 1 < args.length) registryUsername = args[++i];
                    break;
                case "--registry-password":
                    if (i + 1 < args.length) registryPassword = args[++i];
                    break;
                case "--register-hash":
                    if (i + 1 < args.length) registerHash = args[++i];
                    break;
                case "--register-addr":
                    if (i + 1 < args.length) registerAddr = args[++i];
                    break;
            }
        }
    }
}
