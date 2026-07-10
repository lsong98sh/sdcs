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
│   ├── server/
│   │   ├── ProxyServer.java      — Selector NIO 事件循环 + 客户端连接管理
│   │   ├── RespCodec.java        — RedisMessage 与命令参数的编解码
│   │   ├── CommandRouter.java    — 命令分类与路由分发
│   │   ├── CommandType.java      — 读/写/复杂命令分类表
│   │   ├── ClientWriter.java     — 函数式接口，虚拟线程写回客户端的回调
│   │   └── ProxyStatusHandler.java — SDCS_PROXY_STATUS 本地处理
│   ├── backend/
│   │   ├── BackendConnectionPool.java — SocketChannel + 虚拟线程连接池
│   │   └── BackendRequestManager.java — 后端读写转发 + quorum 协调
│   ├── registry/
│   │   └── RegistryManager.java  — JDBC 路由表读取 + 本地缓存 + Proxy心跳
│   └── config/
│       └── ProxyConfig.java      — 配置加载
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

## SDCS Proxy

SDCS Proxy 是一个无状态的 RESP 协议分片代理，将客户端请求按 CRC16 哈希槽路由到后端 SDCS 节点。它不参与数据存储，只负责协议转发。

### 架构

```
Client  ──→  [Selector 事件循环]  ──→  RESP 解码  ──→  CommandRouter
                     │                                        │
                     │                                  虚拟线程 dispatch
                     │                                        │
                     │                              ┌──────────┼──────────┐
                     │                              ▼          ▼          ▼
                     │                        BackendA   BackendB   BackendC
                     │                        (6379)     (6380)     (6381)
                     │                              │
                     │                        虚拟线程 processLoop
                     │                        (BlockingQueue + VT)
                     │                              │
                     └───────────── ←───────────────┘
                              有序投递 (序列号)
```

### 线程模型

Proxy 采用双层线程架构，将 I/O 事件分发与请求处理分离：

**1. Selector 事件循环（1 个固定线程）**

```java
// ProxyServer.java — 单线程 Selector 管理所有客户端连接
selector.select(500);
if (key.isAcceptable())  acceptClient(key);   // 接受新连接
if (key.isReadable())    handleRead(key);      // 读取客户端的 RESP 请求
if (key.isWritable())    handleWrite(key);     // 写 RESP 响应回客户端
```

- 所有客户端连接的 accept、read、write 集中在一个线程处理
- 无锁并发，无需担心线程安全问题
- 写操作通过 `SelectionKey.OP_WRITE` 注册精确控制写就绪

**2. 后端虚拟线程（每连接 1 个 VT）**

```java
// BackendConnectionPool.java — 后端连接在虚拟线程中线性读写
void processLoop() {
    while (active.get()) {
        BackendRequest req = requestQueue.take();
        channel.write(req.requestBytes);      // 阻塞写
        RedisMessage resp = readResponse();   // 阻塞读
        req.future.complete(resp);            // 完成 future
    }
}
```

- 每个后端连接一个虚拟线程，代码为线性同步风格
- 请求通过 `LinkedBlockingQueue` 排队，`CompletableFuture` 异步等待响应
- 虚拟线程在后端 I/O 阻塞时自动挂起，不占用系统线程资源

### 请求生命周期

```
1. Selector 检测到客户端可读
       │
2. 读取 SocketChannel → ByteBuffer(8KB)
       │
3. RespDecoder.decode() 逐字节解析 RESP 协议
       │
4. 提取命令名 + key → CRC16(key) % 1024 → 计算哈希槽
       │
5. RegistryManager.lookup(slot) → 获取后端地址列表
       │
6. 判断命令类型：
   ├─ 读命令 → 随机选一个副本
   ├─ 写命令 → 转发到所有副本（quorum 确认）
   └─ 复杂命令 → 转发到全 slot 节点
       │
7. 虚拟线程分配 seq → BackendConnectionPool.send(requestBytes)
       │
8. 虚拟线程等待 CompletableFuture（自动挂起）
       │
9. BackendConnection.processLoop() 处理完成 → future.complete(resp)
       │
10. 虚拟线程调用 ClientWriter.write(resp, seq) →
    响应存入 ConcurrentSkipListMap[seq] → selector.wakeup()
       │
11. Selector 检测到挂起响应 → pollNextDeliverable()
    按 seq 顺序投递到客户端
```

### 有序投递（Pipeline 保序）

同一客户端连接上发送的多个命令可能路由到不同后端（不同 key 的哈希槽不同），后端响应到达顺序无法保证。

```
Client 发送:  GET a(slot=1)  GET b(slot=2)  GET c(slot=1)
                seq=0          seq=1          seq=2

后端响应到达:  [resp_b,  seq=1]  ← slot=2 快，先到
               [resp_a,  seq=0]  ← slot=1 慢，后到
               [resp_c,  seq=2]

有序投递:       resp_a(seq=0) → resp_b(seq=1) → resp_c(seq=2)
                ↑ 等待 seq=0 到达后才开始投递
```

每个客户端连接维护独立的状态：

```java
class ClientContext {
    final AtomicLong nextCmdSeq = new AtomicLong(0);             // 命令序列号
    final AtomicLong nextDeliverSeq = new AtomicLong(0);         // 期望投递序号
    final ConcurrentSkipListMap<Long, RedisMessage> orderedResponses; // 排序缓冲
}
```

- 每个命令分配单调递增的 `seq`
- 后端响应到达时存入 `ConcurrentSkipListMap[seq]`
- 事件循环每次检查 `map[nextDeliverSeq]` 是否就绪，按序投递
- 仅在响应乱序到达时产生等待成本，正常状态无开销

### 后端连接池

```
BackendConnectionPool
│
├── 按后端地址分组（每组一个连接池）
│   ├── SocketChannel 连接
│   └── 虚拟线程 processLoop（requestQueue→write→read→complete）
│
├── 请求排队
│   ├── LinkedBlockingQueue<BackendRequest>
│   └── CompletableFuture<RedisMessage> 异步等待
│
└── 配置
    ├── backend.min-idle=2      最小空闲连接数
    └── backend.max-total=16    最大连接数
```

后端连接按 `IP:PORT` 分别管理，每个后端连接独立运行一个虚拟线程。请求通过 `LinkedBlockingQueue` 提交到对应连接，虚拟线程完成读写后通过 `CompletableFuture` 通知调用方。

### 写确认（Quorum）

写命令（SET/DEL/EXPIRE 等）会转发到同一哈希槽的**所有副本**：

```
3 副本: 节点A, 节点B, 节点C
         │       │       │
[SET foo bar] → 并行转发到所有副本
         │       │       │
         ▼       ▼       ▼
        +OK     +OK    +OK
         │       │       │
         └─── 等待 quorum ───┘
               > 50% 确认
               (3 副本 → 2 确认)
               
               ✔ 返回 +OK 给客户端
```

- 等待超过 50% 的副本确认写入即返回成功
- 剩余副本异步等待（不阻塞客户端）
- 超过 50% 副本失败才返回错误

### 路由表管理

```java
// RegistryManager — 定时从数据库读取路由表
ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
scheduler.scheduleAtFixedRate(() -> {
    // SELECT addr, hash_start, hash_end, status
    // FROM sdcs_routes WHERE status=1 AND last_heartbeat > NOW() - 60s
    routes = loadFromDatabase();
    buildRoutingIndex();
}, 0, refreshIntervalSeconds, TimeUnit.SECONDS);
```

- 启动时全量加载路由表，之后每 10 秒增量刷新
- `last_heartbeat` 超过 60 秒的节点视为下线，自动从路由表移除
- 支持 SQLite / MySQL / PostgreSQL / H2 作为注册表数据库
- Proxy 也通过 `sdcs_proxies` 表上报心跳，供 Monitor 服务发现

### SDCS_PROXY_STATUS 命令

连接 Proxy 后发送 `SDCS_PROXY_STATUS` 命令，直接获取运行时状态（不路由到后端）：

```
> SDCS_PROXY_STATUS
*6
$3
127.0.0.1:16379
$10
3600
$1
5
$5
1.0.0
*3
*3
$15
127.0.0.1:6379
$1
2
$1
2
```

字段说明：

| 字段 | 类型 | 说明 |
|:-----|:-----|:-----|
| addr | String | Proxy 地址 |
| uptime_secs | Integer | 运行时长 |
| clients | Integer | 当前客户端连接数 |
| version | String | 版本号 |
| backends | Array | 后端连接状态（[地址, 活跃数, 空闲数]） |

### 路由策略

| 命令类型 | 示例 | 路由策略 |
|:---------|:-----|:---------|
| 读命令（单 key） | GET / EXISTS / TTL / TYPE | CRC16(key) → slot → 随机选一个副本 |
| 写命令（单 key） | SET / DEL / EXPIRE / LPUSH | CRC16(key) → slot → 所有副本写入（quorum 确认） |
| 多 key 同 slot | MSET{k1,v1,k2,v2} | 所有 key 哈希到同一 slot 时直接转发 |
| 多 key 跨 slot | MSET{k1,v1,k2,v2} | 返回 CROSSSLOT 错误 |
| 无 key | PING / INFO / TIME / ECHO | 随机选一个后端 |
| 监控 | SDCS_PROXY_STATUS | 本地处理，不路由后端 |

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
