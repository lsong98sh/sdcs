# SDCS — Sonic Data Cache Server

SDCS 是一个与 Redis 协议兼容的高性能分布式缓存系统，采用 Java 25 虚拟线程和纯 JDK NIO 构建，零外部网络依赖。

## 设计理念

### 零外部依赖的 RESP 协议栈

SDCS 的 RESP 编解码器完全基于 `java.nio` 自研，无任何第三方协议依赖。`RespDecoder` 采用状态机逐字节解析，结合 `ByteArrayChain` 链式字节数组处理大消息（单条最大 512MB），无固定缓冲区截断问题。

### Hybrid NIO + 虚拟线程

- **客户端连接**：单线程 Selector NIO 事件循环，所有客户端连接的 accept/read/write 集中处理，固定 1 个线程支撑数千连接。
- **后端请求**：每个后端连接分配一个虚拟线程处理线性读写，代码简单无状态机。
- **跨层协调**：`ConcurrentLinkedQueue` + `selector.wakeup()`，虚拟线程将响应入队后唤醒事件循环投递，无锁通信。

### 去中心化集群

使用**共享数据库作为注册中心**，代替 Gossip 协议或 ZooKeeper：

- SDCS 节点启动时写入 `sdcs_routes` 表，每秒心跳更新 `last_heartbeat`
- Proxy 定时从同一数据库读取路由表，CRC16 % 1024 哈希路由
- 每个 SDCS 节点无集群感知，天然支持水平扩展

### 三种持久化模式

| 模式 | 说明 |
|------|------|
| `rdb_aof` | RDB 快照 + AOF 追加日志（默认，兼容 Redis 方案） |
| `rocksdb` | RocksDB LSM-Tree 嵌入式持久化 |
| `none` | 纯内存模式 |

## 模块架构

```
sdcs
├── sdcs-server          — 核心缓存服务，RESP 协议兼容，数据分片节点
│   ├── protocol/        — 自研 RESP 编解码（RespDecoder, RespEncoder）
│   ├── command/         — 命令分发 + 各类型命令处理器
│   ├── store/           — 内存存储引擎（含 DatabaseManager）
│   ├── persistence/     — 持久化层（RDB, AOF, RocksDB）
│   ├── server/          — NIO Selector 事件循环服务
│   ├── replication/     — 复制管理
│   ├── transaction/     — 事务支持
│   ├── pubsub/          — 发布订阅
│   ├── scripting/       — Lua 脚本引擎
│   ├── blocking/        — 阻塞命令支持
│   ├── common/          — Redis 数据结构（Hash, List, Set, ZSet, Stream）
│   ├── metrics/         — 监控指标 + Prometheus 导出
│   └── registry/        — 注册中心客户端（JDBC 心跳）
│
├── sdcs-proxy           — RESP 协议分片代理
│   ├── server/          — Selector NIO 事件循环 + RESP 解码
│   ├── backend/         — 后端连接池（SocketChannel + 虚拟线程）
│   ├── registry/        — 路由表管理（JDBC 读取 + 本地缓存）
│   └── config/          — 配置加载
│
└── sdcs-monitor         — 集群监控
    ├── http/            — HTTP API（/cluster, /proxies, /health）
    ├── registry/        — 注册表读取
    └── resources/       — 前端页面
```

## 快速开始

### 构建

```bash
# Java 25+ 要求
mvn clean package -pl sdcs-server -am -DskipTests
```

### 启动 SDCS 节点

```bash
# 纯内存模式
java --enable-preview \
  -cp sdcs-server/target/sdcs-server-1.0.0.jar \
  com.qkinfotech.bizwax.sdcs.SDCSServer \
  --port 6379 --bind 0.0.0.0

# RDB + AOF 模式（默认），并注册到数据库
java --enable-preview \
  -cp sdcs-server/target/sdcs-server-1.0.0.jar \
  com.qkinfotech.bizwax.sdcs.SDCSServer \
  --port 6379 --bind 0.0.0.0 \
  --persistence rdb_aof \
  --register-addr 192.168.1.10:6379 --register-hash 0-511 \
  --registry-jdbc-url jdbc:sqlite:sdcs.db
```

### 启动 Proxy

```bash
# config.properties
# proxy.port=16379
# proxy.bind=0.0.0.0
# proxy.addr=192.168.1.10:16379
# registry.jdbc-url=jdbc:sqlite:sdcs.db

java --enable-preview \
  -cp sdcs-proxy/target/sdcs-proxy-1.0.0.jar \
  com.qkinfotech.bizwax.sdcs.proxy.SDCSProxy \
  config.properties
```

### 启动 Monitor

```bash
# monitor.properties
# http.port=8080
# http.bind=0.0.0.0
# registry.jdbc-url=jdbc:sqlite:sdcs.db

java --enable-preview \
  -cp sdcs-monitor/target/sdcs-monitor-1.0.0.jar \
  com.qkinfotech.bizwax.sdcs.monitor.SDCSMonitorApp \
  monitor.properties
```

### 使用 redis-cli 连接

```bash
# 直连 SDCS 节点
redis-cli -p 6379 PING
redis-cli -p 6379 SET foo bar
redis-cli -p 6379 GET foo

# 通过 Proxy 连接（跨后端请求自动路由）
redis-cli -p 16379 SET mykey hello
redis-cli -p 16379 GET mykey
redis-cli -p 16379 INFO

# Proxy 状态查询
redis-cli -p 16379 SDCS_PROXY_STATUS
```

## Proxy 路由策略

| 命令类型 | 示例 | 路由策略 |
|:---------|:-----|:---------|
| 读命令（单 key） | GET / EXISTS / TTL | CRC16(key) → slot → 随机选一个副本 |
| 写命令（单 key） | SET / DEL / EXPIRE | CRC16(key) → slot → 所有副本写入 |
| 多 key 同 slot | MSET / MGET | 多个 key 必须在同一 slot |
| 多 key 跨 slot | MSET{k1,v1,k2,v2} | 返回 CROSSSLOT 错误 |
| 无 key | PING / INFO / TIME | 随机选一个后端 |
| 监控 | SDCS_PROXY_STATUS | 直接返回，不路由后端 |

### Pipeline 保序

Proxy 为每个客户端连接维护序列号和有序投递机制。当同一连接上的多个命令路由到不同后端时，后端响应即使乱序到达，Proxy 也会按序列号有序投递，保证客户端接收顺序与发送顺序一致。

## 配置

### SDCS 节点配置

| 参数 | 默认值 | 说明 |
|:-----|:-------|:-----|
| `--port` | 6379 | 监听端口 |
| `--bind` | 0.0.0.0 | 绑定地址 |
| `--persistence` | rdb_aof | 持久化模式（rdb_aof / rocksdb / none） |
| `--register-addr` | - | 注册地址（IP:PORT），启用注册中心 |
| `--register-hash` | - | 负责的哈希槽范围（如 0-511） |
| `--registry-jdbc-url` | - | 注册表 JDBC 连接串 |
| `--registry-username` | - | 数据库用户 |
| `--registry-password` | - | 数据库密码 |

### Proxy 配置

| 参数 | 默认值 | 说明 |
|:-----|:-------|:-----|
| `proxy.port` | 16379 | 监听端口 |
| `proxy.bind` | 0.0.0.0 | 绑定地址 |
| `proxy.addr` | bind:port | 对外地址（bind=0.0.0.0 时必须显式设置） |
| `proxy.max-connections` | 10000 | 最大客户端连接数 |
| `registry.jdbc-url` | - | 注册表 JDBC 连接串 |
| `registry.refresh-interval-seconds` | 10 | 路由表刷新间隔 |
| `registry.heartbeat-timeout-seconds` | 60 | 心跳超时阈值 |
| `backend.min-idle` | 2 | 后端连接池最小空闲数 |
| `backend.max-total` | 16 | 后端连接池最大连接数 |

### Monitor 配置

| 参数 | 默认值 | 说明 |
|:-----|:-------|:-----|
| `http.port` | 8080 | HTTP 端口 |
| `http.bind` | 0.0.0.0 | 绑定地址 |
| `registry.jdbc-url` | - | 注册表 JDBC 连接串 |

## 监控

Monitor 提供 HTTP API 和前端页面：

| 路径 | 说明 |
|:-----|:-----|
| `/` | 前端仪表盘（每 3 秒自动刷新） |
| `/cluster` | 节点路由表 JSON |
| `/proxies` | Proxy 心跳信息 JSON |
| `/health` | 健康检查 |

SDCS 节点还支持 Prometheus 指标导出（配置 `--metrics-port`）。

## 测试

```bash
# 全量测试（含覆盖率报告）
mvn clean test -pl sdcs-server -am

# Proxy 测试
mvn clean test -pl sdcs-proxy -am

# 全部模块测试
mvn clean test
```

测试类别涵盖：功能测试、压力测试、高并发测试、边界测试、安全测试。

## 技术栈

- Java 25（虚拟线程、模式匹配、Record）
- 纯 JDK NIO（无 Netty 依赖）
- JDBC + SQLite/MySQL/PostgreSQL/H2（注册表存储）
- RocksDB（嵌入式持久化引擎）
- SLF4J + Logback（日志）
- JUnit 5 + JaCoCo（测试 + 覆盖率）
