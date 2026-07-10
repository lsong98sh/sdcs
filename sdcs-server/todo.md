# SDCS (Sonic Data Cache Server) — 待办与完成清单

> 最近更新: 2026-07-03 | 代码审计，重新梳理任务优先级

---

## 一、命令补全 🎯

### 1.1 高优先级 — Stream 消费组 / 占位实现补齐

| 命令 | 状态 | 说明 |
|:----|:----:|:------|
| `XGROUP CREATE/SETID/DESTROY/DELCONSUMER` | ❌ | Stream 消费者组管理，消息队列核心能力 |
| `XREADGROUP` | ❌ | 消费者组读取消息，阻塞消费场景 |
| `XACK` | ❌ | 消息确认，至少一次语义保证 |
| `XCLAIM` | ❌ | 消息转移 / 超时重投 |
| `XINFO` | ❌ | Stream / 消费组信息查询 |
| `XPENDING` | ◐ | 当前返回空数组，需完整实现待处理消息视图 |

### 1.2 高优先级 — 复制协议完整实现

当前状态：SLAVEOF/PSYNC 仅为**骨架实现**——TCP 连接建立后无 RDB 传输、无命令传播、无心跳，实际无法工作。

| 模块 | 状态 | 说明 |
|:----|:----:|:------|
| `SLAVEOF` 命令补齐 | ❌ | 缺少 PING/REPLCONF/AUTH 握手，无 `REPLICAOF` 别名 |
| `REPLCONF` 协议 | ❌ | 缺少 listening-port / ip-address / capa / ack 子命令 |
| `PSYNC` 全量同步 | ❌ | 回复 FULLRESYNC 后不发送 RDB 数据，客户端永远等待 |
| **RDB 传输通道** | ❌ | 主节点需在 PSYNC 后 dump RDB 快照并推送到从节点 socket |
| **命令传播** | ❌ | `ReplicationManager.propagateCommand()` 为空，写操作不转发 |
| **Backlog 写入** | ❌ | `ReplicationBacklog.append()` 从未被调用，offset 永远为 0 |
| **repl-id 维护** | ❌ | 硬编码固定值，不随角色切换重新生成 |
| **从节点只读** | ❌ | 无写命令拦截，从节点可自由写入 |
| **复制心跳** | ❌ | 无 PING/REPLCONF ACK，无 `repl-timeout` 断连机制 |
| **自动连接主节点** | ❌ | 启动时 `--replicaof` 配置未读取应用 |
| **从节点重连** | ❌ | 连接断开无自动重试 |
| **INFO replication** | ◐ | 仅输出 role/connected_slaves/offset，缺 repl_id/slave0:ip/backlog 等 |

### 1.3 中优先级 — 数据结构命令补齐

| 命令 | 类别 | 说明 |
|:----|:----:|:------|
| `HEXPIRE / HPEXPIRE` | Hash | 字段级过期（Redis 7.4+） |
| `BITFIELD` | Bitmap | 位字段批量操作 |
| `GEOSEARCH` | Geo | 地理搜索（已有 Geo 基础设施） |
| `LCS` | String | 最长公共子序列 |
| `OBJECT` | Generic | 内部对象编码 / 空闲时间 / 引用计数 |
| `SCRIPT KILL` | Scripting | 杀死正在运行的 Lua 脚本 |
| `FCALL` | Scripting | Redis 函数调用 |
| `EVAL/EVALSHA 真实 Lua 运行时` | Scripting | 当前为纯字符串模式匹配伪实现，需集成 LuaJ 或 Luaj-jse 支持完整 Lua 5.1 语法 |

### 1.4 低优先级 — 连接/服务器 / 高级功能

| 命令 | 说明 |
|:----|:------|
| `WAIT` | 等待从节点同步确认 |
| `REPLCONF` | 复制配置协议 |
| `MEMORY DOCTOR / PURGE / STATS / USAGE` | 内存分析诊断 |
| `CLIENT` 子命令补齐 | KILL/PAUSE/UNPAUSE/TRACKING/CACHING/NO-EVICT 等 |
| `ACL` 全套命令 | 用户 / 权限管理 |
| `LOLWUT` | 彩蛋命令 |
| `SENTINEL` | 哨兵高可用 |

---

## 二、性能优化 ⚡

### 2.1 RESP 协议解析
- [ ] **RespDecoder 零拷贝优化** — 当前 bulk string 读取存在冗余拷贝
- [ ] **Pipeline 批量处理** — 连续命令合并解析，减少系统调用
- [ ] **堆外内存缓冲** — DirectByteBuffer 减少 GC 压力

### 2.2 数据结构
- [ ] **大 Key 渐进式删除** — UNLINK 的异步后台删除（当前 DEL 同步阻塞）
- [ ] **MemoryStore 压缩** — 空闲 ziplist / quicklist 压缩
- [ ] **RDB 写入零拷贝** — 利用文件通道直接写入

### 2.3 持久化
- [ ] **RocksDB 写入调优** — batch size 自适应
- [ ] **AOF 写入合并** — 多个小命令合并 fsync
- [ ] **RDB 非阻塞持久化** — 写时复制 fork 优化（当前 BGSAVE 后台线程）

### 2.4 并发
- [ ] **StripedLock 竞争度监控** — 统计每个 stripe 的竞争次数，动态调整数量
- [ ] **读路径无锁化** — 当前 ConcurrentHashMap 已无锁，验证所有读路径

---

## 三、测试增强 🧪

### 3.1 随机/压力测试
- [ ] **模糊测试 (Fuzz)** — 随机 RESP 字节流输入，验证服务端不崩溃
- [ ] **内存泄漏检测** — 长时间运行后 heap dump 分析
- [ ] **并发安全测试** — 多线程交替写入/读取/过期，验证无数据损坏
- [ ] **网络分区模拟** — 客户端断连、重连、半开连接场景

### 3.2 协议兼容性
- [ ] **RESP3 兼容性** — RESP3 客户端连接测试（redis-cli 7.x）
- [ ] **Jedis / Lettuce / Redisson 兼容性矩阵** — 主流 Java 客户端自动化测试
- [ ] **redis-benchmark 对标** — 同场景对比 SDCS vs Redis 吞吐 / 延迟

### 3.3 混沌工程
- [ ] **磁盘写入延迟注入** — AOF / RDB 写入 hang 场景
- [ ] **内存限制 OOM 测试** — 接近 maxmemory 时驱逐策略验证
- [ ] **主从复制故障切换** — 模拟从节点断连 / 全量同步失败

---

## 四、可观测性 📊

### 4.1 Metrics 增强
- [ ] **命令级延迟分布** — P50/P99/P999 每个命令的耗时直方图
- [ ] **热点 Key 监控** — 访问频率最高的 Top N key
- [ ] **慢查询持久化** — SLOWLOG 写入独立日志文件
- [ ] **内存按 DB/类型统计** — 按数据库和数据类型分类内存占用

### 4.2 Prometheus / Grafana
- [ ] **Grafana 官方仪表盘** — 提供 dashboard JSON 模板（连接数、QPS、延迟、内存）
- [ ] **告警规则** — Prometheus 告警规则模板（高延迟、OOM 风险、复制延迟）

### 4.3 日志 / 追踪
- [ ] **结构化日志 (JSON)** — 支持 logstash 格式直接入 ES
- [ ] **OpenTelemetry 集成** — 链路追踪支持

---

## 五、工具链与运维 🛠️

### 5.1 部署与配置
- [ ] **Docker 官方镜像** — 多架构（amd64 + arm64）构建
- [ ] **Kubernetes Helm Chart** — 一键部署模板
- [ ] **配置文件热加载** — `CONFIG REWRITE` + 运行时重载
- [ ] **maxmemory 驱逐策略** — allkeys-lru / volatile-lru / allkeys-random 等

### 5.2 迁移与兼容
- [ ] **RDB 文件互导** — 兼容 Redis RDB 格式，支持 `redis-cli --pipe` 导入
- [ ] **在线数据迁移工具** — 从 Redis 平滑迁移到 SDCS
- [ ] **redis-cli 完整兼容性验证** — `redis-cli info / config / monitor / debug` 等子命令

### 5.3 CLI / 管理端点
- [ ] **HTTP 管理 API** — /health、/metrics、/info JSON 端点
- [ ] **命令行管理工具** — `sdcs-cli` 独立管理客户端

---

## 六、架构演进 🏗️

### 6.1 复制协议完整实现（前提条件）

复制是分布式分片的基础，当前为骨架实现，需先完成：

- [ ] **全量同步流程** — PSYNC 后主节点 fork 子线程 dump RDB，通过 socket 推送到从节点
- [ ] **增量命令传播** — `ReplicationManager.propagateCommand()` 写操作 → backlog → 转发所有从节点
- [ ] **Backlog 环形缓冲区** — 写入实际命令数据，支持部分重同步判断
- [ ] **repl-id / repl-offset 生命周期** — 启动时随机生成，角色切换时重新生成
- [ ] **复制握手协议** — SLAVEOF → PING → REPLCONF(listening-port/ip-address/capa) → PSYNC
- [ ] **从节点只读约束** — 角色为 SLAVE 时拒绝写命令
- [ ] **复制心跳 / 超时断连** — 主节点定期 PING，从节点 REPLCONF ACK，`repl-timeout` 断连
- [ ] **自动连接主节点** — 启动时读取 `--replicaof` 自动发起 SLAVEOF
- [ ] **断线重连** — 复制连接断开后指数退避重试
- [ ] **INFO replication 补齐** — repl_id、slave0:ip/port/state/offset/lag、master_link_status、backlog 信息

### 6.2 分布式分片层
- [ ] **Proxy 层设计**
  - 独立代理服务器，解析 RESP 后按 key 路由到后端
  - 一致性哈希分片（支持虚拟节点）
- [ ] **双层分片架构**
  - Level 1：Region 层（跨机房/地域）
  - Level 2：Node 层（机房内数据分片）
  - 层级之间独立扩容，互不影响
- [ ] **Proxy 无状态**
  - 代理层不存储数据，纯路由转发
  - 支持水平扩展

### 6.2 多线程模型优化 ✅
- ✅ **当前模型**：全局 `ReentrantLock` → **64 分段锁 (`StripedLock`)**
- ✅ **RocksDB 写入**：已异步化（队列 → 批量写入），不阻塞响应
- ✅ **读操作**：`ConcurrentHashMap` 完全无锁
- ✅ **验证**：147 个测试全绿，压测 QPS 49 万（读+写），长稳 242 万 ops/sec

---

## 已完成 ✅

### 已实现命令

| 类别 | 数量 | 命令列表 |
|:----|:----:|:---------|
| **String** | 20/20 | SET, GET, MGET, MSET, SETNX, GETSET, GETEX, GETDEL, SETEX, PSETEX, INCR, INCRBY, DECR, DECRBY, INCRBYFLOAT, APPEND, STRLEN, GETRANGE, SETRANGE, MSETNX |
| **List** | 20/20 | LPUSH, RPUSH, LPOP, RPOP, LLEN, LINDEX, LRANGE, LSET, LTRIM, LREM, LINSERT, LPOS, LMOVE, RPOPLPUSH, LPUSHX, RPUSHX, BLPOP, BRPOP, BLMOVE, BRPOPLPUSH |
| **Hash** | 15/15 | HSET, HGET, HMSET, HMGET, HGETALL, HDEL, HLEN, HEXISTS, HKEYS, HVALS, HINCRBY, HINCRBYFLOAT, HSTRLEN, HSETNX, HRANDFIELD |
| **Set** | 15/15 | SADD, SREM, SMEMBERS, SISMEMBER, SCARD, SPOP, SRANDMEMBER, SMOVE, SUNION, SINTER, SDIFF, SUNIONSTORE, SINTERSTORE, SDIFFSTORE, SMISMEMBER |
| **ZSet** | 32/32 | ZADD, ZREM, ZSCORE, ZCARD, ZRANK, ZREVRANK, ZINCRBY, ZCOUNT, ZRANGE, ZREVRANGE, ZRANGEBYSCORE, ZREVRANGEBYSCORE, ZREMRANGEBYRANK, ZREMRANGEBYSCORE, ZPOPMIN, ZPOPMAX, ZMSCORE, ZRANDMEMBER, ZDIFF, ZINTER, ZUNION, ZDIFFSTORE, ZINTERSTORE, ZUNIONSTORE, ZRANGESTORE, ZLEXCOUNT, ZRANGEBYLEX, ZREVRANGEBYLEX, ZREMRANGEBYLEX, BZPOPMIN, BZPOPMAX, ZINTERCARD |
| **键管理** | 29/29 | DEL, EXISTS, KEYS, DBSIZE, TYPE, EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT, TTL, PTTL, PERSIST, FLUSHDB, FLUSHALL, RENAME, RENAMENX, RANDOMKEY, SELECT, SCAN, SSCAN, HSCAN, ZSCAN, COPY, MOVE, TOUCH, SORT, DUMP, RESTORE |
| **连接/服务器** | 17/22 | PING, ECHO, QUIT, AUTH, HELLO, CONFIG, SELECT, INFO, TIME, LASTSAVE, SHUTDOWN, SLOWLOG, ROLE, DEBUG, CLIENT, MONITOR, COMMAND |
| **事务** | 5/5 | MULTI, EXEC, DISCARD, WATCH, UNWATCH |
| **发布订阅** | 6/6 | PUBLISH, SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE, PUBSUB |
| **持久化** | 5/5 | SAVE, BGSAVE, BGREWRITEAOF, LASTSAVE, SHUTDOWN |
| **Bitmap** | 5/6 | SETBIT, GETBIT, BITCOUNT, BITOP, BITPOS |
| **HyperLogLog** | 3/3 | PFADD, PFCOUNT, PFMERGE |
| **Geo** | 6/7 | GEOADD, GEODIST, GEOPOS, GEORADIUS, GEORADIUSBYMEMBER, GEOHASH |
| **Stream** | 7/13 | XADD, XLEN, XRANGE, XREVRANGE, XREAD, XTRIM, XDEL |
| **Lua 脚本** | 5/7 ◐ | EVAL(◐), EVALSHA(◐), SCRIPT LOAD, SCRIPT EXISTS, SCRIPT FLUSH |
| **复制** | 2/2 ◐ | SLAVEOF, PSYNC（骨架实现，无实际数据传输） |

### 稳定性修复 ✅
- C1 客户端断连泄漏 → 补 removeNode
- C2 事务状态泄漏 → cleanup 回调
- C3 AOF 无界队列 → ArrayBlockingQueue + 背压
- C4 DEBUG SLEEP 阻塞 → 限制 1~30s
- C5 RESP 无上限 → bulk 512MB / array 100000
- H1-H7 空 catch、fsync 静默、等待列表无界、缓冲区溢出等

### 安全加固 ✅
- S1 密码隐藏、S2 CONFIG 校验、S3 SHUTDOWN 确认、S4 FLUSH 确认

### 测试 ✅
- 事务 / PubSub / Stream / Lua / 阻塞 / Geo / 持久化 / 复制 / 认证 / 边界
- 147 个测试全绿

### 压测 ✅
- 50 并发 10 万操作全成功，GC 压力 5 万次循环 0 次触发
- 长稳 7500 万次 ~250 万 ops/sec

### 工具链 ✅
- checkstyle / Xlint 编译警告 / 错误码规范化

---

## 进度统计

| 类别 | 已实现 | 覆盖率 |
|:----|:-----:|:------:|
| **String** | 20/20 | **100%** |
| **List** | 20/20 | **100%** |
| **Hash** | 15/15 | **100%** |
| **Set** | 15/15 | **100%** |
| **ZSet** | 32/32 | **100%** |
| **键管理** | 29/29 | **100%** |
| **事务** | 5/5 | **100%** |
| **发布订阅** | 6/6 | **100%** |
| **持久化** | 5/5 | **100%** |
| **连接/服务器** | 17/22 | **77%** |
| **Bitmap** | 5/6 | **83%** |
| **HyperLogLog** | 3/3 | **100%** |
| **Geo** | 6/7 | **86%** |
| **Stream** | 7/13 | **54%** |
| **Lua 脚本** | 5/7 ◐ | **71%** |
| **复制** | 2/2 ◐ | **骨架** |
| **总计** | **~190** | **~75%** |
| **常用命令 (Top 50)** | **~50** | **~100%** |

---

> **SDCS (Sonic Data Cache Server) v1.0** — 覆盖 Redis ~75% 命令总数（~100% 核心命令），支持三种持久化模式、16 数据库、RocksDB 持久化、发布订阅、Lua 脚本(伪实现, ◐)、JMX+Prometheus 可观测性。复制协议为骨架实现，需进一步开发方可工作。
