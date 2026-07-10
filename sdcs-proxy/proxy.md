# SDCS Proxy 设计文档

> 版本: v0.1 | 日期: 2026-07-03

---

## 1. 架构概述

```
                    client (redis-cli / Jedis / Lettuce ...)
                           │
                           ▼
                    ┌──────────────┐
                    │  sdcs-proxy  │  ← RESP 路由器
                    │  (无状态)     │
                    └──────┬───────┘
                           │
              ┌────────────┼────────────┐
              ▼            ▼            ▼
        ┌──────────┐ ┌──────────┐ ┌──────────┐
        │ sdcs-A   │ │ sdcs-B   │ │ sdcs-C   │
        │ hash=0-3 │ │ hash=4-6 │ │ hash=7-9 │
        └──────────┘ └──────────┘ └──────────┘
              │            │            │
        ┌──────────┐ ┌──────────┐
        │ sdcs-A1  │ │ sdcs-A2  │  ← 同一 hash 范围，互为副本
        │ hash=0-3 │ │ hash=0-3 │
        └──────────┘ └──────────┘

                    ┌──────────────────────┐
                    │  共享注册表 (MySQL)   │
                    │  sdcs_routes 表       │
                    │  ─────────────       │
                    │  addr, hash_start,   │
                    │  hash_end, status,   │
                    │  last_heartbeat      │
                    └──────────────────────┘
                           ↑         ↑
              SDCS 节点写入     Proxy 读取
```

### 核心思想

- **Proxy 是 RESP 路由器**：通过查询共享数据库获取路由表，转发 RESP 命令到对应后端
- **SDCS 零集群感知**：后端是纯 RESP 服务器，无集群协议、无 Gossip、无 slot 迁移
- **注册表由数据库承载**：SDCS 节点写入 JDBC 数据库，Proxy 读取同一数据库，天然多实例共享
- **复制 = 注册同一 hash 范围**：多个节点注册同一 hash 段，自动收到相同命令。写命令转发所有副本，超过 50% 确认即返回
- **分片 = 不同 hash 范围**：不同节点注册不同 hash 段，数据自然隔离

### ⏳ 待讨论

以下复杂命令无法通过单 key hash 定位分片，分片模式下的路由策略待定。Phase 1 统一处理逻辑：

```
如果路由表中存在 hash=0-1023 的全 slot 节点：
  复杂命令统一转发到该节点执行
否则：
  返回错误 ERR Command not supported in sharded mode (no all-slot node)
```

> 全 slot 节点 = 注册 `hash=0-1023` 的 SDCS 实例，因为写命令转发所有副本，它天然拥有全量数据。

| 命令 | 当前策略 | 需要确认的问题 |
|:-----|:---------|:---------------|
| PUBLISH / SUBSCRIBE | 有全 slot 则转发，否则报错 | Pub/Sub 在分片模式下是否需要跨分片？全 slot 节点能否覆盖所有频道？ |
| SCAN / HSCAN / SSCAN / ZSCAN | 同上 | 全 slot 节点扫描结果足够，无需合并 |
| SELECT | 同上 | 全 slot 节点负责所有 db，语义可保持 |
| MULTI / EXEC | 同上 | 事务内 key 可能跨分片，全 slot 节点可处理 |
| SORT | 同上 | 全 slot 节点有全量数据，BY/GET 可正常执行 |
| EVAL / EVALSHA / EVAL_RO / SCRIPT | 同上 | 全 slot 节点执行脚本，操作任意 key |

---

## 2. 核心概念

### 2.1 路由表

Proxy 内部维护一张 `hash_range → [node_addr, ...]` 映射表：

```
0-511   → 192.168.1.10:6379, 192.168.1.11:6379  (主 + 副本)
512-767 → 192.168.1.20:6379, 192.168.1.21:6379  (主 + 副本)
768-1023 → 192.168.1.30:6379                      (单节点)
```

- 每个 hash 范围对应一个**分片**
- 一个分片可对应**多个节点**（复制）
- 不同分片的 hash 范围可**重叠**，重叠部分意味着容错——同一 slot 对应多个分片组，路由时随机选一组
- 路由表**动态更新**：节点上线/下线时注册/注销

### 2.2 Hash 算法

```
slot = CRC16(key) % 1024
```

- **1024 个 slot** 空间（0-1023）
- 分片注册时指定 slot 范围，如 `register-hash=0-511`（占用 512 个 slot）
- Proxy 根据 slot 查找分片，再随机选一个节点转发

### 2.3 注册数据库表

SDCS 节点和 Proxy 通过**共享数据库**交换路由信息，不依赖 Proxy 实例之间的直连通信。

**表结构（MySQL/SQLite 通用）：**

```sql
CREATE TABLE sdcs_routes (
    addr          VARCHAR(64)  NOT NULL,      -- IP:PORT
    hash_start    INT          NOT NULL,      -- slot 范围起始
    hash_end      INT          NOT NULL,      -- slot 范围结束
    status        TINYINT      DEFAULT 1,     -- 1=ONLINE, 0=OFFLINE
    last_heartbeat DATETIME    NOT NULL,      -- 最近一次心跳时间
    created_at    DATETIME     NOT NULL,
    updated_at    DATETIME     NOT NULL,
    PRIMARY KEY (addr)
);
```

**操作方式：**

| 操作 | SDCS 节点行为 | Proxy 行为 |
|:-----|:--------------|:-----------|
| **注册** | `INSERT INTO sdcs_routes ... ON DUPLICATE KEY UPDATE status=1` | 无需操作 |
| **心跳** | 每秒 `UPDATE sdcs_routes SET last_heartbeat=NOW() WHERE addr=?` | 无需操作 |
| **注销** | `UPDATE sdcs_routes SET status=0 WHERE addr=?` | 路由表自动移除 |
| **读取** | 无需操作 | 启动时 `SELECT * FROM sdcs_routes WHERE status=1`，之后每 10s 刷新 |

**配置方式：**

```properties
# SDCS 侧（新增 JDBC 配置）
registry.jdbc-url=jdbc:mysql://192.168.1.200:3306/sdcs
registry.username=root
registry.password=xxx
registry.driver=com.mysql.cj.jdbc.Driver

# Proxy 侧（新增 JDBC 配置，与 SDCS 指向同一数据库）
registry.jdbc-url=jdbc:mysql://192.168.1.200:3306/sdcs
registry.username=root
registry.password=xxx
registry.driver=com.mysql.cj.jdbc.Driver
```

> 开发期可使用 SQLite 替代 MySQL：`registry.jdbc-url=jdbc:sqlite:sdcs.db`，零运维启动。

---

## 3. SDCS 服务端改动

### 3.1 新增配置

SDCS 需要新增以下配置项，以便启动时向注册表写入节点信息：

| 配置项 | 说明 | 示例 |
|:-------|:-----|:-----|
| `registry.jdbc-url` | 注册表数据库 JDBC URL | `jdbc:mysql://192.168.1.200:3306/sdcs` |
| `registry.username` | 数据库用户名 | `root` |
| `registry.password` | 数据库密码 | `xxx` |
| `registry.driver` | JDBC 驱动类 | `com.mysql.cj.jdbc.Driver` |
| `register-hash` | 本节点负责的 slot 范围 | `0-511` |
| `register-addr` | 本节点对外地址 | `192.168.1.10:6379` |

### 3.2 启动流程

```
SDCS 启动
  │
  ├─ 加载配置（含 registry.jdbc-url / register-hash）
  │
  ├─ 启动 RESP 服务（端口 6379）
  │
  ├─ 如果配置了 registry.jdbc-url：
  │     ├─ 初始化 JDBC 连接池（HikariCP）
  │     ├─ 执行 INSERT/UPDATE sdcs_routes（upsert）
  │     └─ 启动心跳线程（每秒 UPDATE last_heartbeat）
  │
  └─ 正常提供服务
```

### 3.3 心跳与断连

```
心跳线程：
  每 1 秒 → UPDATE sdcs_routes SET last_heartbeat=NOW() WHERE addr=?
  如果数据库连接断开 → 重连 JDBC（指数退避 1s, 2s, 4s... max 30s）
  重连成功后恢复心跳

Proxy 端超时检测（与服务端无关）：
  Proxy 每 10s 扫描一次路由表
  last_heartbeat > 60s 前的节点 → 标记 OFFLINE
  节点恢复后自动重新注册（UPSERT + 心跳）→ Proxy 下次扫描时发现并恢复 ONLINE
```

### 3.4 SDCS 端改动范围

| 模块 | 改动 | 复杂度 |
|:-----|:-----|:-------|
| `SDCSConfig.java` | 新增 `registryJdbcUrl`, `registryUsername`, `registryPassword`, `registryDriver`, `registerHash`, `registerAddr` | 低 |
| `SDCSServer.java` | 启动流程中插入注册逻辑 | 低 |
| **新增** `RegistryClient.java` | JDBC 连接池初始化 + UPSERT + 心跳线程 + 重连 | 中 |
| **新增** Maven 依赖 | HikariCP + MySQL/SQLite JDBC 驱动 | 低 |
| 其余代码 | **零改动** | - |

---

## 4. Proxy 核心设计

> 技术栈：**Netty**（网络层）+ RESP codec（`io.netty.handler.codec.redis.*`）+ Java 21 虚拟线程（业务转发）

### 4.1 组件架构

```
sdcs-proxy
├── ProxyServer.java          ── 主入口，Netty ServerBootstrap
├── RegistryManager.java      ── 路由表管理（从数据库读取 + 本地缓存）
├── Router.java               ── CRC16 % 1024 路由
├── BackendConnectionPool.java ── 后端连接池（Netty 连接复用）
├── RespRouter.java           ── RESP 协议解析 + 转发逻辑
└── config/
    └── ProxyConfig.java      ── 配置
```

### 4.2 请求处理流程

```
1. 客户端连接 Proxy（端口 16379）
2. 发送 RESP 命令：SET foo bar
3. Proxy 解析 RESP，提取命令名 + key
4. Router.hash("foo") → slot = CRC16("foo") % 1024
5. RegistryManager.lookup(slot) → [192.168.1.10:6379, 192.168.1.11:6379]
6. 查读/写命令表判断命令类型：
   - 写命令 → **转发给列表中所有节点**（保证副本一致性）
   - 读命令 → 随机或 round-robin 选一个节点
7. 转发完整 RESP 命令到后端连接
8. 等待后端 RESP 响应
   - 写命令：等待**超过 50%** 的副本返回成功即视为写成功，其余副本异步重试（最多 3 次）
   - 读命令：等待单个节点响应
9. 将响应返回给客户端
```

> **读/写命令分类**：使用硬编码命令表 `Map<String, Boolean> isWriteCommand` 标记每条命令，如 SET/DEL/EXPIRE/LPUSH→写，GET/EXISTS/TTL/TYPE→读。约 180 条命令，覆盖全部已在 SDCS 实现的命令。

> **写确认策略**（quorum）：等待超过 50% 的副本确认写入即返回 OK（如 3 副本需 2 确认，2 副本需 2 确认，1 副本需 1 确认）。未确认的副本内部异步重试，最多 3 次。超过 50% 副本失败才返回错误。

### 4.3 线程模型

**双层架构：Netty EventLoop（IO）+ Java 21 虚拟线程（业务）**

```
Client ──→ [Netty EventLoop] ──→ RESP 解码
                    │
         虚拟线程：hash(key) → Router.lookup(slot) → 后端列表
                    │
         虚拟线程：并行转发到所有后端（写）或单个后端（读）
                    │
         虚拟线程：等待响应 → 组装 RESP → 回写 Netty
```

- **Netty EventLoop** 负责网络 IO（编解码、收发包），不阻塞
- **虚拟线程** 负责业务逻辑（路由计算、转发等待），等后端响应时挂起不占资源
- `CompletableFuture.allOf()` 并行转发到多个副本，虚拟线程自动等待

### 4.4 多 key 命令处理

对于涉及多个 key 的命令（如 MSET、SUNIONSTORE、ZINTERSTORE）：

1. 提取所有 key
2. 计算每个 key 的 slot
3. 如果所有 key 落在**同一 slot** → 直接转发
4. 如果 key 跨 slot → **返回错误** `ERR CROSSSLOT keys in request don't hash to the same slot`

> 与 Redis Cluster 行为一致。

### 4.5 连接池

```
BackendConnectionPool
├── 每个后端地址一个 Netty 连接池
│   ├── 最小连接数: 2
│   ├── 最大连接数: 16
│   ├── 空闲超时: 60s
│   └── 健康检查: 每 30s PING
└── 连接复用
    ├── 命令转发后不关闭连接
    └── 虚拟线程通过 CompletableFuture 异步等待响应
```

### 4.6 节点健康检测

基于数据库心跳时间戳，所有 Proxy 实例共享同一份健康状态判断：

```
RegistryManager
├── 路由表刷新（定时任务）
│   ├── 每 10s → SELECT * FROM sdcs_routes WHERE status=1
│   ├── 对比缓存路由表，识别变更
│   ├── last_heartbeat > 60s 前的节点 → 跳过（视为不可用）
│   └── 更新本地缓存路由表
├── TCP 连接断连处理
│   ├── Proxy 到后端的 Netty 连接断开（TCP RST / 超时）
│   ├── 连接池自动重连，保持最小连接数
│   ├── 重连成功后路由表不变，继续使用
│   └── 重连持续失败 → 依赖心跳超时（60s）自然剔除
├── 节点恢复
│   ├── SDCS 节点重新注册（UPSERT + 心跳）
│   └── Proxy 下次（≤10s）扫描时发现并恢复 ONLINE
└── 事件通知
    ├── 节点上线 → 更新路由表
    ├── 节点下线 → 更新路由表
    └── （可选）日志/告警
```

---

## 5. 配置示例

### 5.1 Proxy 配置

```properties
# proxy/config.properties
proxy.port=16379
proxy.bind=0.0.0.0
proxy.max-connections=10000

# 注册表数据库（与 SDCS 节点指向同一库）
registry.jdbc-url=jdbc:mysql://192.168.1.200:3306/sdcs
registry.username=root
registry.password=xxx
registry.driver=com.mysql.cj.jdbc.Driver
```

### 5.2 SDCS 节点配置

```properties
# sdcs-A/config.properties
port=6379
register-addr=192.168.1.10:6379
register-hash=0-511

registry.jdbc-url=jdbc:mysql://192.168.1.200:3306/sdcs
registry.username=root
registry.password=xxx
registry.driver=com.mysql.cj.jdbc.Driver

# sdcs-B/config.properties
port=6379
register-addr=192.168.1.20:6379
register-hash=512-1023

registry.jdbc-url=jdbc:mysql://192.168.1.200:3306/sdcs
registry.username=root
registry.password=xxx
registry.driver=com.mysql.cj.jdbc.Driver

# sdcs-A1 (副本，与 A 同一 hash 范围)
port=6380
register-addr=192.168.1.11:6379
register-hash=0-511

registry.jdbc-url=jdbc:mysql://192.168.1.200:3306/sdcs
registry.username=root
registry.password=xxx
registry.driver=com.mysql.cj.jdbc.Driver
```

---

## 6. 扩缩容

### 6.1 扩容（新增分片）

```
1. 启动新的 SDCS 节点，配置 register-hash=256-511
2. 节点启动时自动插入 sdcs_routes 到数据库
3. 所有 Proxy 在下次（≤10s）扫描时发现新节点并更新路由表：
   0-255   → 192.168.1.10:6379
   256-511 → 192.168.1.30:6379   ← 新增
   512-1023 → 192.168.1.20:6379
4. 新命令按新路由表转发
```

> 注意：已有数据不会自动迁移，需要单独的数据搬迁工具。

### 6.2 增加副本（扩容读能力）

```
1. 启动新的 SDCS 节点，配置 register-hash=0-511（与现有主节点一致）
2. 节点启动时自动写入数据库，Proxy 扫描后路由表更新：
   0-511 → 192.168.1.10:6379, 192.168.1.11:6379  ← 新增副本
3. Proxy 读请求可随机选择任一节点
```

### 6.3 缩容

```
1. 关闭 SDCS 节点前，执行：UPDATE sdcs_routes SET status=0 WHERE addr=?
2. Proxy 下次（≤10s）扫描时发现状态变更，移除该节点
3. 如果该分片还有剩余节点，继续服务
4. 如果该分片无剩余节点，返回错误 `ERR SLOT xxx not covered`
```

---

## 7. 命令路由规则

| 命令类型 | 示例 | 路由策略 |
|:---------|:-----|:---------|
| 读命令（单 key） | GET/EXISTS/TTL/TYPE | hash(key) → slot → 分片 → 随机选一个副本 |
| 写命令（单 key） | SET/DEL/EXPIRE/LPUSH | hash(key) → slot → 分片 → **所有副本** |
| 多 key 同 slot | MSET/MGET | hash(所有 key) → 相同 slot → 同分片 |
| 多 key 跨 slot | MSET {k1,v1,k2,v2} | 返回 CROSSSLOT 错误 |
| 无 key | PING/INFO/TIME | 随机选择一个节点 |
| 数据库级 | FLUSHDB/SELECT | 所有分片广播（需小心） |
| 事务 | MULTI/EXEC | 事务内所有 key 必须同 slot |

---

## 8. 限制与已知缺口

### 当前设计限制

1. **多 key 跨 slot 操作不支持** — 与 Redis Cluster 行为一致
2. **无自动数据迁移** — 扩缩容后数据需外部工具搬迁（见 Phase 5）
3. **复杂命令策略待定** — Pub/Sub、SCAN、SELECT、MULTI、SORT、EVAL/SCRIPT 的路由策略待讨论。有全 slot 节点时可转发，否则报错（见 §1 ⏳ 待讨论）
4. **写放大** — 写命令转发到所有副本，副本数越多延迟越高（N 个副本 ≈ N 倍尾部延迟）

### 已知缺口（后续阶段补齐）

| 缺口 | 影响 | 方案 |
|:-----|:-----|:-----|
| **数据搬迁工具** | 扩容新分片后，旧数据不会自动迁移到新节点 | 开发独立搬迁工具，支持单机 SDCS 和 Proxy 两种部署模式的数据搬迁（Phase 5） |

---

## 9. 开发计划

### Phase 1：基础框架
- [ ] ProxyServer — Netty ServerBootstrap + RESP codec
- [ ] RegistryManager — 路由表管理（JDBC 读取 + 本地缓存 + 10s 刷新）
- [ ] Router — CRC16 % 1024 路由
- [ ] BackendConnectionPool — 后端 Netty 连接池（虚拟线程异步转发）

### Phase 2：命令路由
- [ ] RespRouter — 单 key 路由转发（读/写命令表 Map）
- [ ] 多 key 同 slot 路由
- [ ] 跨 slot 错误检测（CROSSSLOT）
- [ ] 写确认 quorum（>50% 确认机制）
- [ ] 响应异步回写客户端

### Phase 3：注册 & 心跳
- [ ] SDCS 端 RegistryClient — JDBC 连接池 + UPSERT + 心跳（每秒 UPDATE）
- [ ] Proxy 端路由表定时刷新 + 健康检测（last_heartbeat 超时）
- [ ] SDCS 端优雅注销（shutdown 时 SET status=0）

### Phase 4：数据搬迁工具
- [ ] 搬迁工具设计：基于 SCAN + DUMP/RESTORE 或 RDB 导入
- [ ] 支持单机 SDCS 搬迁（扩容/合并）
- [ ] 支持 Proxy 模式搬迁（新增分片数据补全）

---

## 6. HiddenBackupNode — 内嵌备份节点

> **任务状态**: 📅 明天实现 (2026-07-04)

### 目标

在 Proxy 进程中嵌入一个**轻量全量备份节点**，实现：
- 所有写命令异步执行，不阻塞主线程
- 按 slot 索引存储 KV 数据，支持**按 slot 范围导出**给新上线节点做数据复制
- 使用 RocksDB 持久化，LRU 读缓存（非全量），内存可控

### 架构

```
Proxy 进程
└── HiddenBackupNode
    ├── 有界异步队列 (LinkedBlockingQueue)
    ├── Worker 线程 × 1~2
    ├── RocksDB 存储
    │   ├── key → value (实际数据体)
    │   ├── key → meta (数据类型、过期时间 PXAT)
    │   └── slot_index → [keys] (按 slot 分组索引)
    ├── LRU Read Cache (少量热点，兜底 RocksDB)
    └── SlotExporter (按 slot 范围批量导出)
```

### 写入流程

```
forwardWrite() → [同步] 真实节点，等 quorum，回客户端
               → [异步] 入队 HiddenBackupNode
                  → Worker 出队
                  → 解析命令，从 RocksDB 读当前值（INCR 等需要）
                  → 执行命令，写 RocksDB
                  → 更新 slot_index
```

### 注意事项

- **所有命令都执行**，INCR/LPUSH 等非幂等命令从 RocksDB 读当前值再执行
- **相对过期时间转为 PXAT 绝对时间戳**写入
- **队列有界**，满了直接丢弃（备节点允许落后）
- **Cache 不是全量**，兜底读 RocksDB，内存不随数据量增长
- **按 slot 索引**，用于新节点上线时的数据导出
