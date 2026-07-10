# SDCS vs Redis 命令功能对比

## 图例
| 符号 | 含义 |
|:----:|------|
| ✅ | 完整支持 |
| ◐ | 部分支持 |
| ❌ | 未实现 |

---

## 1. 连接/服务器 (Connection/Server)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| PING | ✅ | ✅ | 完全兼容 |
| ECHO | ✅ | ✅ | 完全兼容 |
| QUIT | ✅ | ✅ | 关闭连接 |
| AUTH | ✅ | ✅ | 连接层处理（SDCSNode），支持 requirepass |
| HELLO | ✅ | ✅ | 返回 proto=3、角色、版本等 |
| CONFIG | ✅ | ✅ | 支持 CONFIG GET/SET |
| SELECT | ✅ | ✅ | 切换数据库索引 |
| INFO | ✅ | ✅ | 返回完整 INFO（服务端、客户端、内存、统计、复制等） |
| TIME | ✅ | ✅ | 返回 Unix 秒和微秒 |
| LASTSAVE | ✅ | ✅ | 返回当前时间戳 |
| SHUTDOWN | ✅ | ✅ | 支持 NOSAVE/SAVE 及 requirepass 确认 |
| SLOWLOG | ✅ | ✅ | 实现 GET/LEN/RESET 子命令 |
| ROLE | ✅ | ✅ | 返回 master 或 slave 角色信息 |
| DEBUG | ✅ | ✅ | 实现 SET-ACTIVE-EXPIRE/SLEEP（SLEEP 限制 1~30 秒） |
| CLIENT | ◐ | ✅ | 实现 SETNAME/GETNAME/LIST/KILL（部分子命令） |
| MONITOR | ◐ | ✅ | 已注册命令，MonitorManager 支持广播 |
| COMMAND | ◐ | ✅ | 返回空数组（占位实现） |
| WAIT | ❌ | ✅ | 等待从节点同步未实现 |
| REPLCONF | ❌ | ✅ | 复制配置协议未实现 |
| MEMORY | ❌ | ✅ | 内存相关命令未实现 |
| LOLWUT | ❌ | ✅ | 彩蛋命令未实现 |
| ACL 系列 | ❌ | ✅ | 访问控制未实现 |

---

## 2. 通用键命令 (Generic Keys)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| DEL | ✅ | ✅ | 完全兼容 |
| EXISTS | ✅ | ✅ | 完全兼容 |
| EXPIRE | ✅ | ✅ | 完全兼容 |
| EXPIREAT | ✅ | ✅ | 完全兼容 |
| PEXPIRE | ✅ | ✅ | 完全兼容 |
| PEXPIREAT | ✅ | ✅ | 完全兼容 |
| TTL | ✅ | ✅ | 完全兼容 |
| PTTL | ✅ | ✅ | 完全兼容 |
| PERSIST | ✅ | ✅ | 完全兼容 |
| TYPE | ✅ | ✅ | 完全兼容 |
| KEYS | ✅ | ✅ | `KEYS pattern` 支持 glob 匹配 |
| DBSIZE | ✅ | ✅ | 完全兼容 |
| FLUSHDB | ✅ | ✅ | 支持 requirepass FORCE 确认 |
| FLUSHALL | ✅ | ✅ | 同上 |
| RENAME | ✅ | ✅ | 完全兼容 |
| RENAMENX | ✅ | ✅ | 完全兼容 |
| RANDOMKEY | ✅ | ✅ | 完全兼容 |
| COPY | ✅ | ✅ | 支持 REPLACE |
| MOVE | ✅ | ✅ | 跨数据库移动 |
| TOUCH | ✅ | ✅ | 更新最后访问时间 |
| SORT | ✅ | ✅ | 支持 ALPHA/ASC/DESC/LIMIT/BY/STORE |
| DUMP | ✅ | ✅ | 使用 RdbEncoder 序列化 |
| RESTORE | ✅ | ✅ | 支持 TTL/REPLACE，使用 RdbDecoder 反序列化 |
| SCAN | ✅ | ✅ | 游标扫描，完全兼容 |
| SSCAN | ✅ | ✅ | Set 游标扫描 |
| HSCAN | ✅ | ✅ | Hash 游标扫描 |
| ZSCAN | ✅ | ✅ | ZSet 游标扫描 |
| OBJECT | ❌ | ✅ | 内部对象信息未实现 |

---

## 3. String 命令

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| SET | ✅ | ✅ | 支持 EX/PX/EXAT/PXAT/NX/XX |
| GET | ✅ | ✅ | 完全兼容 |
| MGET | ✅ | ✅ | 完全兼容 |
| MSET | ✅ | ✅ | 完全兼容 |
| SETNX | ✅ | ✅ | `SET key value NX` |
| SETEX | ✅ | ✅ | 完全兼容 |
| PSETEX | ✅ | ✅ | 完全兼容 |
| INCR | ✅ | ✅ | 完全兼容 |
| INCRBY | ✅ | ✅ | 完全兼容 |
| DECR | ✅ | ✅ | 完全兼容 |
| DECRBY | ✅ | ✅ | 完全兼容 |
| INCRBYFLOAT | ✅ | ✅ | 浮点数自增，完全兼容 |
| APPEND | ✅ | ✅ | 完全兼容 |
| STRLEN | ✅ | ✅ | 完全兼容 |
| GETRANGE | ✅ | ✅ | 完全兼容（SUBSTR） |
| SETRANGE | ✅ | ✅ | 完全兼容 |
| GETSET | ✅ | ✅ | 原子设置并返回旧值 |
| GETEX | ✅ | ✅ | 支持 EX/PX/EXAT/PXAT/PERSIST |
| GETDEL | ✅ | ✅ | GET 并删除 |
| MSETNX | ✅ | ✅ | 批量条件设置 |
| LCS | ❌ | ✅ | 最长公共子序列未实现 |

---

## 4. List 命令

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| LPUSH | ✅ | ✅ | 完全兼容 |
| RPUSH | ✅ | ✅ | 完全兼容 |
| LPUSHX | ✅ | ✅ | 仅当 key 存在时推入 |
| RPUSHX | ✅ | ✅ | 同上 |
| LPOP | ✅ | ✅ | 完全兼容 |
| RPOP | ✅ | ✅ | 完全兼容 |
| BLPOP | ✅ | ✅ | 支持 timeout 阻塞 |
| BRPOP | ✅ | ✅ | 支持 timeout 阻塞 |
| BLMOVE | ✅ | ✅ | 阻塞版 LMOVE（SDCSCommandExecutor 处理） |
| BRPOPLPUSH | ✅ | ✅ | 可用 BLPOP+LPUSH 替代（非原子） |
| LLEN | ✅ | ✅ | 完全兼容 |
| LINDEX | ✅ | ✅ | 完全兼容 |
| LRANGE | ✅ | ✅ | 完全兼容 |
| LSET | ✅ | ✅ | 完全兼容 |
| LTRIM | ✅ | ✅ | 完全兼容 |
| LREM | ✅ | ✅ | 完全兼容 |
| LINSERT | ✅ | ✅ | 完全兼容 |
| RPOPLPUSH | ✅ | ✅ | 完全兼容 |
| LMOVE | ✅ | ✅ | 非阻塞版 BLMOVE |
| LPOS | ✅ | ✅ | 支持 RANK/COUNT/MAXLEN |

---

## 5. Hash 命令

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| HSET | ✅ | ✅ | 完全兼容 |
| HGET | ✅ | ✅ | 完全兼容 |
| HMSET | ✅ | ✅ | 完全兼容 |
| HMGET | ✅ | ✅ | 完全兼容 |
| HGETALL | ✅ | ✅ | 完全兼容 |
| HDEL | ✅ | ✅ | 完全兼容 |
| HLEN | ✅ | ✅ | 完全兼容 |
| HEXISTS | ✅ | ✅ | 完全兼容 |
| HKEYS | ✅ | ✅ | 完全兼容 |
| HVALS | ✅ | ✅ | 完全兼容 |
| HINCRBY | ✅ | ✅ | 完全兼容 |
| HINCRBYFLOAT | ✅ | ✅ | 完全兼容 |
| HSTRLEN | ✅ | ✅ | 完全兼容 |
| HSETNX | ✅ | ✅ | 条件设置字段 |
| HRANDFIELD | ✅ | ✅ | 支持 WITHVALUES 和 count |
| HEXPIRE | ❌ | ✅ | 字段级过期（Redis 7.4+） |
| HPEXPIRE | ❌ | ✅ | 同上 |

---

## 6. Set 命令

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| SADD | ✅ | ✅ | 完全兼容 |
| SREM | ✅ | ✅ | 完全兼容 |
| SMEMBERS | ✅ | ✅ | 完全兼容 |
| SISMEMBER | ✅ | ✅ | 完全兼容 |
| SCARD | ✅ | ✅ | 完全兼容 |
| SPOP | ✅ | ✅ | 完全兼容 |
| SRANDMEMBER | ✅ | ✅ | 完全兼容 |
| SMOVE | ✅ | ✅ | 完全兼容 |
| SUNION | ✅ | ✅ | 完全兼容 |
| SINTER | ✅ | ✅ | 完全兼容 |
| SDIFF | ✅ | ✅ | 完全兼容 |
| SUNIONSTORE | ✅ | ✅ | 结果存入目标 key |
| SINTERSTORE | ✅ | ✅ | 结果存入目标 key |
| SDIFFSTORE | ✅ | ✅ | 结果存入目标 key |
| SMISMEMBER | ✅ | ✅ | 批量成员检查 |

---

## 7. ZSet (有序集合) 命令

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| ZADD | ✅ | ✅ | 完全兼容（支持 NX/XX/CH/INCR） |
| ZREM | ✅ | ✅ | 完全兼容 |
| ZSCORE | ✅ | ✅ | 完全兼容 |
| ZRANK | ✅ | ✅ | 完全兼容 |
| ZREVRANK | ✅ | ✅ | 完全兼容 |
| ZCARD | ✅ | ✅ | 完全兼容 |
| ZCOUNT | ✅ | ✅ | 完全兼容 |
| ZRANGE | ✅ | ✅ | 完全兼容（支持 WITHSCORES） |
| ZREVRANGE | ✅ | ✅ | 完全兼容（支持 WITHSCORES） |
| ZRANGEBYSCORE | ✅ | ✅ | 完全兼容（支持 LIMIT） |
| ZREVRANGEBYSCORE | ✅ | ✅ | 完全兼容（支持 LIMIT） |
| ZREMRANGEBYRANK | ✅ | ✅ | 完全兼容 |
| ZREMRANGEBYSCORE | ✅ | ✅ | 完全兼容 |
| ZINCRBY | ✅ | ✅ | 完全兼容 |
| ZPOPMIN | ✅ | ✅ | 弹出最小元素 |
| ZPOPMAX | ✅ | ✅ | 弹出最大元素 |
| BZPOPMIN | ✅ | ✅ | 阻塞版 ZPOPMIN（SDCSCommandExecutor 处理） |
| BZPOPMAX | ✅ | ✅ | 阻塞版 ZPOPMAX（SDCSCommandExecutor 处理） |
| ZRANGESTORE | ✅ | ✅ | 范围结果存到新 key |
| ZRANDMEMBER | ✅ | ✅ | 随机成员 |
| ZDIFF | ✅ | ✅ | 差集（支持 WITHSCORES） |
| ZINTER | ✅ | ✅ | 交集（支持 WEIGHTS/AGGREGATE/WITHSCORES） |
| ZUNION | ✅ | ✅ | 并集（支持 WEIGHTS/AGGREGATE/WITHSCORES） |
| ZDIFFSTORE | ✅ | ✅ | 差集存入 |
| ZINTERSTORE | ✅ | ✅ | 交集存入（支持 WEIGHTS/AGGREGATE） |
| ZUNIONSTORE | ✅ | ✅ | 并集存入（支持 WEIGHTS/AGGREGATE） |
| ZMSCORE | ✅ | ✅ | 批量获取 score |
| ZLEXCOUNT | ✅ | ✅ | 字典序范围内计数 |
| ZRANGEBYLEX | ✅ | ✅ | 字典序范围查询 |
| ZREVRANGEBYLEX | ✅ | ✅ | 逆序字典序范围 |
| ZREMRANGEBYLEX | ✅ | ✅ | 删除字典序范围 |
| ZINTERCARD | ✅ | ✅ | ZSet 交集基数 |

---

## 8. 事务 (Transaction)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| MULTI | ✅ | ✅ | 完全兼容 |
| EXEC | ✅ | ✅ | 完全兼容（支持 WATCH 乐观锁） |
| DISCARD | ✅ | ✅ | 完全兼容 |
| WATCH | ✅ | ✅ | 完全兼容（key 被修改则 EXEC 返回空） |
| UNWATCH | ✅ | ✅ | 完全兼容 |

---

## 9. 发布订阅 (Pub/Sub)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| PUBLISH | ✅ | ✅ | 完全兼容 |
| SUBSCRIBE | ✅ | ✅ | 连接级处理，完全兼容 |
| UNSUBSCRIBE | ✅ | ✅ | 连接级处理，完全兼容 |
| PSUBSCRIBE | ✅ | ✅ | 模式匹配订阅，连接级处理 |
| PUNSUBSCRIBE | ✅ | ✅ | 模式匹配取消，连接级处理 |
| PUBSUB | ✅ | ✅ | 实现 CHANNELS/NUMSUB/NUMPAT |

---

## 10. 持久化 (Persistence)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| SAVE | ✅ | ✅ | 完全兼容（同步 RDB 写入） |
| BGSAVE | ✅ | ✅ | 完全兼容（后台 RDB 写入） |
| BGREWRITEAOF | ✅ | ✅ | 完全兼容（AOF 重写） |
| LASTSAVE | ✅ | ✅ | 上次保存时间 |
| SHUTDOWN | ✅ | ✅ | 支持 NOSAVE/SAVE 及 requirepass 确认 |

---

## 11. 脚本 (Scripting)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| EVAL | ◐ | ✅ | 伪实现：纯字符串模式匹配，不支持变量/循环/条件/运算等 Lua 语法 |
| EVALSHA | ◐ | ✅ | 伪实现：仅缓存查找 + SHA 匹配，底层实现同 EVAL |
| SCRIPT LOAD | ✅ | ✅ | 脚本加载（SHA-1 哈希 + 缓存） |
| SCRIPT EXISTS | ✅ | ✅ | 脚本存在检查 |
| SCRIPT FLUSH | ✅ | ✅ | 脚本缓存清空 |
| SCRIPT KILL | ❌ | ✅ | 终止脚本执行未实现 |
| FCALL | ❌ | ✅ | Redis 函数调用未实现 |

---

## 12. 位图 (Bitmap)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| SETBIT | ✅ | ✅ | 位设置 |
| GETBIT | ✅ | ✅ | 位获取 |
| BITCOUNT | ✅ | ✅ | 位计数（支持范围） |
| BITOP | ✅ | ✅ | 支持 AND/OR/XOR/NOT |
| BITPOS | ✅ | ✅ | 查找位位置 |
| BITFIELD | ❌ | ✅ | 位字段操作未实现 |

---

## 13. HyperLogLog

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| PFADD | ✅ | ✅ | 基数估算添加 |
| PFCOUNT | ✅ | ✅ | 基数估算计数（支持多键） |
| PFMERGE | ✅ | ✅ | 基数合并 |

---

## 14. Geo (地理位置)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| GEOADD | ✅ | ✅ | 基于 ZSet 实现 |
| GEOPOS | ✅ | ✅ | 位置查询 |
| GEODIST | ✅ | ✅ | 距离计算（支持单位转换） |
| GEORADIUS | ✅ | ✅ | 支持 WITHCOORD/WITHDIST/COUNT |
| GEORADIUSBYMEMBER | ✅ | ✅ | 基于成员的半径查询 |
| GEOHASH | ✅ | ✅ | GeoHash 编码 |
| GEOSEARCH | ❌ | ✅ | 地理搜索未实现 |

---

## 15. Stream (流)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| XADD | ✅ | ✅ | 流添加 |
| XLEN | ✅ | ✅ | 流长度 |
| XRANGE | ✅ | ✅ | 流范围查询（支持 COUNT） |
| XREVRANGE | ✅ | ✅ | 流逆序范围查询 |
| XREAD | ✅ | ✅ | 流读取（支持 COUNT 和多流） |
| XTRIM | ✅ | ✅ | 流裁剪（支持 MAXLID 和近似修剪） |
| XDEL | ✅ | ✅ | 消息删除（支持多 ID） |
| XPENDING | ◐ | ✅ | 返回空数组（占位实现） |
| XGROUP | ❌ | ✅ | 消费组管理未实现（返回错误） |
| XREADGROUP | ❌ | ✅ | 消费组读取未实现（返回错误） |
| XACK | ❌ | ✅ | 消息确认未实现 |
| XCLAIM | ❌ | ✅ | 消息转移未实现 |
| XINFO | ❌ | ✅ | 流信息查询未实现 |

---

## 16. 管理与集群 (Admin & Cluster)

| 命令 | SDCS | Redis | 备注 |
|:----|:----:|:-----:|:-----|
| SLAVEOF | ◐ | ✅ | 骨架实现：可切换角色、建立 TCP 连接，但无 RDB 传输/命令传播/心跳 |
| REPLICAOF | ❌ | ✅ | 未注册，SLAVEOF 等价别名 |
| REPLCONF | ❌ | ✅ | 复制配置协议未实现 |
| PSYNC | ◐ | ✅ | 骨架实现：回复 FULLRESYNC 但后续不发送 RDB 数据 |
| ROLE | ✅ | ✅ | 角色查询 |
| WAIT | ❌ | ✅ | 等待从节点同步未实现 |
| CLUSTER 系列 | ❌ | ✅ | 完整集群子系统 |
| SENTINEL 系列 | ❌ | ✅ | 哨兵子系统 |
| ACL 系列 | ❌ | ✅ | 访问控制 |

---

## 总体统计

| 类别 | 已实现 | Redis 总数 | 覆盖率 |
|:----|:------:|:----------:|:------:|
| **连接/服务器** | 16 | ~22 | 73% |
| **通用键命令** | 27 | ~28 | 96% |
| **String** | 20 | ~21 | 95% |
| **List** | 20 | 20 | **100%** |
| **Hash** | 15 | ~17 | 88% |
| **Set** | 15 | 15 | **100%** |
| **ZSet** | 32 | ~32 | **100%** |
| **事务** | 5 | 5 | **100%** |
| **发布订阅** | 6 | ~6 | **100%** |
| **持久化** | 5 | ~5 | **100%** |
| **脚本/函数** | 5 (3 ◐) | ~7 | 71% |
| **位图** | 5 | ~6 | 83% |
| **HyperLogLog** | 3 | 3 | **100%** |
| **Geo** | 6 | ~7 | 86% |
| **Stream** | 7 | ~13 | 54% |
| **集群/哨兵** | 1 | ~50 | 2% |
| **总计** | **187 (5 ◐)** | **~255** | **~73%** |
| **常用命令** *(Top 50)* | **~50** | **~50** | **~100%** |

> **说明**：SDCS 已在 v1.0 阶段覆盖了绝大多数 Redis 核心数据结构命令。
> - String、List、Set、ZSet、Hash、事务、Pub/Sub、持久化覆盖率接近或达到 100%
> - 新增位图（5/6）、HyperLogLog（3/3）、Geo（6/7）、Stream（7/13）、脚本（5/7）支持
> - 缺失的主要是：Stream 消费组（XGROUP/XREADGROUP/XACK）、ACL 安全认证、集群/哨兵分布式部署
> - ⚠️ **复制协议为骨架实现**：SLAVEOF/PSYNC 命令已注册可返回响应，但无实际的 RDB 数据传输和命令传播，不能用于生产环境
> - ⚠️ **Lua 脚本为伪实现**：EVAL/EVALSHA 仅支持 `return redis.call('CMD', KEYS[n], ARGV[n])` 简单模式，不支持变量/循环/条件/算术运算。无真实 Lua 运行时依赖（如 LuaJ），测试仅覆盖简单模式。
> - 连接层新增了 AUTH、HELLO、CONFIG、SELECT 等补充

## 已实现命令完整列表（190 个）

```
PING, ECHO, QUIT, AUTH, HELLO, CONFIG, SELECT, INFO, TIME,
LASTSAVE, SHUTDOWN, SLOWLOG, ROLE, DEBUG, CLIENT, MONITOR, COMMAND,
SET, GET, MGET, MSET, SETNX, SETEX, PSETEX,
INCR, INCRBY, DECR, DECRBY, INCRBYFLOAT,
APPEND, STRLEN, GETRANGE, SETRANGE, GETSET, GETEX, GETDEL, MSETNX,
LPUSH, RPUSH, LPUSHX, RPUSHX, LPOP, RPOP,
BLPOP, BRPOP, BLMOVE, BRPOPLPUSH,
LLEN, LINDEX, LRANGE, LSET, LTRIM, LREM, LINSERT, RPOPLPUSH, LMOVE, LPOS,
HSET, HGET, HMSET, HMGET, HGETALL, HDEL,
HLEN, HEXISTS, HKEYS, HVALS,
HINCRBY, HINCRBYFLOAT, HSTRLEN, HSETNX, HRANDFIELD,
SADD, SREM, SMEMBERS, SISMEMBER, SCARD, SPOP,
SRANDMEMBER, SMOVE, SUNION, SINTER, SDIFF,
SUNIONSTORE, SINTERSTORE, SDIFFSTORE, SMISMEMBER,
ZADD, ZREM, ZSCORE, ZRANK, ZREVRANK, ZCARD,
ZCOUNT, ZRANGE, ZREVRANGE, ZRANGEBYSCORE,
ZREVRANGEBYSCORE, ZREMRANGEBYRANK, ZREMRANGEBYSCORE, ZINCRBY,
ZPOPMIN, ZPOPMAX, BZPOPMIN, BZPOPMAX,
ZRANGESTORE, ZRANDMEMBER, ZDIFF, ZINTER, ZUNION,
ZDIFFSTORE, ZINTERSTORE, ZUNIONSTORE, ZMSCORE,
ZLEXCOUNT, ZRANGEBYLEX, ZREVRANGEBYLEX, ZREMRANGEBYLEX, ZINTERCARD,
EXPIRE, PEXPIRE, EXPIREAT, PEXPIREAT, TTL, PTTL, PERSIST,
DEL, EXISTS, KEYS, DBSIZE, TYPE, FLUSHDB, FLUSHALL,
RENAME, RENAMENX, RANDOMKEY, COPY, MOVE, TOUCH, SORT,
DUMP, RESTORE, SCAN, SSCAN, HSCAN, ZSCAN,
SAVE, BGSAVE, BGREWRITEAOF,
MULTI, EXEC, DISCARD, WATCH, UNWATCH,
PUBLISH, SUBSCRIBE, UNSUBSCRIBE, PSUBSCRIBE, PUNSUBSCRIBE, PUBSUB,
EVAL(◐), EVALSHA(◐), SCRIPT,
SETBIT, GETBIT, BITCOUNT, BITOP, BITPOS,
PFADD, PFCOUNT, PFMERGE,
GEOADD, GEOPOS, GEODIST, GEORADIUS, GEORADIUSBYMEMBER, GEOHASH,
XADD, XLEN, XRANGE, XREVRANGE, XREAD, XTRIM, XDEL, XPENDING,
SLAVEOF, PSYNC
```
