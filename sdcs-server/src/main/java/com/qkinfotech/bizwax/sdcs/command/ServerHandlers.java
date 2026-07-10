package com.qkinfotech.bizwax.sdcs.command;

import com.qkinfotech.bizwax.sdcs.protocol.RedisMessage;
import com.qkinfotech.bizwax.sdcs.store.DatabaseManager;
import com.qkinfotech.bizwax.sdcs.store.MemoryStore;
import com.qkinfotech.bizwax.sdcs.persistence.PersistenceManager;
import com.qkinfotech.bizwax.sdcs.metrics.MetricsCollector;
import com.qkinfotech.bizwax.sdcs.metrics.SlowLog;
import com.qkinfotech.bizwax.sdcs.metrics.MonitorManager;
import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.pubsub.PubSubManager;
import com.qkinfotech.bizwax.sdcs.server.NIOServer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public class ServerHandlers extends BaseHandler {

    public ServerHandlers(DatabaseManager db, PersistenceManager pm) {
        super(db, pm);
    }

    public RedisMessage handleDbSize(List<RedisMessage> args) {
        return RedisMessage.integer(store().size());
    }

    public RedisMessage handleFlushDb(List<RedisMessage> args) {
        if (requiresForceConfirmation(args)) {
            return RedisMessage.error("ERR FLUSHDB requires FORCE confirmation when requirepass is set");
        }
        store().clear();
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleSelect(List<RedisMessage> args) {
        if (args.size() != 1) {
            return RedisMessage.error("wrong number of arguments for 'select' command");
        }
        try {
            int db = Integer.parseInt(args.get(0).asString());
            databaseManager.select(db);
            store();
            return RedisMessage.simpleString("OK");
        } catch (IllegalArgumentException e) {
            return RedisMessage.error(e.getMessage());
        }
    }

    public RedisMessage handleFlushAll(List<RedisMessage> args) {
        if (requiresForceConfirmation(args)) {
            return RedisMessage.error("ERR FLUSHALL requires FORCE confirmation when requirepass is set");
        }
        databaseManager.flushAll();
        store();
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleInfo(List<RedisMessage> args) {
        MetricsCollector mc = MetricsCollector.getInstance();
        MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
        MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();
        Runtime rt = Runtime.getRuntime();

        long totalCommands = mc.getTotalCommands();
        long totalConnections = mc.getTotalConnections();
        long currentConns = mc.getCurrentConnections();
        long totalErrors = mc.getTotalErrors();
        long totalBytesRead = mc.getTotalBytesRead();
        long totalBytesWritten = mc.getTotalBytesWritten();
        long uptime = mc.getUptimeSeconds();
        long usedMemory = rt.totalMemory() - rt.freeMemory();

        double opsPerSec = uptime > 0 ? (double) totalCommands / uptime : 0;
        double hitRate = totalCommands > 0 ? (1.0 - (double) totalErrors / totalCommands) * 100 : 100;

        StringBuilder info = new StringBuilder();
        info.append("# Server\n");
        info.append("redis_version:6.2.0\n");
        info.append("os:").append(System.getProperty("os.name")).append("\n");
        info.append("arch:").append(System.getProperty("os.arch")).append("\n");
        info.append("process_id:").append(ManagementFactory.getRuntimeMXBean().getPid()).append("\n");
        info.append("tcp_port:6379\n");
        info.append("uptime_in_seconds:").append(uptime).append("\n");
        info.append("uptime_in_days:").append(uptime / 86400).append("\n");

        info.append("# Clients\n");
        info.append("connected_clients:").append(currentConns).append("\n");
        info.append("total_connections_received:").append(totalConnections).append("\n");
        info.append("blocked_clients:0\n");
        info.append("max_clients:10000\n");

        info.append("# Memory\n");
        info.append("used_memory:").append(usedMemory).append("\n");
        info.append("used_memory_human:").append(String.format("%.2fM", usedMemory / (1024.0 * 1024.0))).append("\n");
        info.append("used_memory_rss:").append(rt.totalMemory()).append("\n");
        info.append("used_memory_peak:").append(heapUsage.getUsed()).append("\n");
        info.append("total_system_memory:").append(Runtime.getRuntime().maxMemory()).append("\n");
        info.append("used_memory_lua:0\n");
        info.append("maxmemory:0\n");
        info.append("maxmemory_policy:noeviction\n");
        info.append("mem_fragmentation_ratio:1.00\n");

        info.append("# Stats\n");
        info.append("total_connections_received:").append(totalConnections).append("\n");
        info.append("total_commands_processed:").append(totalCommands).append("\n");
        info.append("instantaneous_ops_per_sec:").append(Math.round(opsPerSec)).append("\n");
        info.append("total_net_input_bytes:").append(totalBytesRead).append("\n");
        info.append("total_net_output_bytes:").append(totalBytesWritten).append("\n");
        info.append("instantaneous_input_kbps:").append(String.format("%.2f", totalBytesRead / 1024.0 / Math.max(uptime, 1))).append("\n");
        info.append("instantaneous_output_kbps:").append(String.format("%.2f", totalBytesWritten / 1024.0 / Math.max(uptime, 1))).append("\n");
        info.append("rejected_connections:0\n");
        info.append("total_error_replies:").append(totalErrors).append("\n");
        info.append("hit_rate_percent:").append(String.format("%.2f", hitRate)).append("\n");
        info.append("keyspace_hits:0\n");
        info.append("keyspace_misses:0\n");
        info.append("pubsub_channels:").append(PubSubManager.getInstance().getChannels().size()).append("\n");
        info.append("pubsub_patterns:0\n");
        info.append("expired_keys:0\n");
        info.append("evicted_keys:0\n");
        info.append("keyspace_misses:0\n");

        info.append("# CPU\n");
        info.append("used_cpu_sys:0.00\n");
        info.append("used_cpu_user:0.00\n");
        info.append("used_cpu_sys_children:0.00\n");
        info.append("used_cpu_user_children:0.00\n");

        info.append("# Keyspace\n");
        for (int i = 0; i < 16; i++) {
            MemoryStore ms = databaseManager.getStore(i);
            if (ms != null && ms.size() > 0) {
                info.append("db").append(i).append(":keys=").append(ms.size()).append(",expires=0,avg_ttl=0\n");
            }
        }

        info.append("# Replication\n");
        info.append("role:master\n");
        info.append("connected_slaves:0\n");
        info.append("master_repl_offset:0\n");

        info.append("# Commandstats\n");
        for (var entry : mc.getCommandStats().entrySet()) {
            String cmd = entry.getKey();
            var stats = entry.getValue();
            double avgMs = stats.getAvgDurationMs();
            double maxMs = stats.getMaxDurationMs();
            info.append("cmdstat_").append(cmd.toLowerCase()).append(":calls=").append(stats.getCount())
                    .append(",usec=").append(Math.round(avgMs * 1000))
                    .append(",usec_per_call=").append(String.format("%.2f", avgMs * 1000))
                    .append(",usec_max=").append(Math.round(maxMs * 1000))
                    .append("\n");
        }

        return RedisMessage.bulkString(info.toString());
    }

    public RedisMessage handleSave(List<RedisMessage> args) {
        if (persistenceManager == null) {
            return RedisMessage.error("ERR persistence not configured");
        }
        try {
            persistenceManager.saveRdb();
            return RedisMessage.simpleString("OK");
        } catch (Exception e) {
            return RedisMessage.error("ERR " + e.getMessage());
        }
    }

    public RedisMessage handleBgSave(List<RedisMessage> args) {
        if (persistenceManager == null) {
            return RedisMessage.error("ERR persistence not configured");
        }
        Thread.ofVirtual().start(() -> {
            try {
                persistenceManager.saveRdb();
            } catch (Exception e) {
                System.err.println("BGSAVE failed: " + e.getMessage());
            }
        });
        return RedisMessage.simpleString("Background saving started");
    }

    public RedisMessage handleBgRewriteAof(List<RedisMessage> args) {
        if (persistenceManager == null) {
            return RedisMessage.error("ERR persistence not configured");
        }
        Thread.ofVirtual().start(() -> {
            try {
                persistenceManager.rewriteAof();
            } catch (Exception e) {
                System.err.println("BGREWRITEAOF failed: " + e.getMessage());
            }
        });
        return RedisMessage.simpleString("Background AOF rewrite started");
    }

    public RedisMessage handleSlowLog(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return RedisMessage.error("ERR wrong number of arguments for 'slowlog' command");
        }
        String subCommand = args.get(0).asString().toUpperCase();
        switch (subCommand) {
            case "GET" -> {
                long count = args.size() > 1 ? Long.parseLong(args.get(1).asString()) : 10;
                List<SlowLog.SlowLogEntry> entries = SlowLog.getInstance().getEntries(count);
                RedisMessage[] result = new RedisMessage[entries.size()];
                for (int i = 0; i < entries.size(); i++) {
                    SlowLog.SlowLogEntry entry = entries.get(i);
                    List<RedisMessage> fields = new ArrayList<>();
                    fields.add(RedisMessage.integer(entry.timestamp()));
                    fields.add(RedisMessage.integer(entry.durationUs()));
                    fields.add(RedisMessage.bulkString(entry.command().getBytes()));
                    RedisMessage[] argArr = new RedisMessage[entry.args().size()];
                    for (int j = 0; j < entry.args().size(); j++) {
                        argArr[j] = RedisMessage.bulkString(entry.args().get(j).getBytes());
                    }
                    fields.add(RedisMessage.array(argArr));
                    fields.add(RedisMessage.bulkString("127.0.0.1:6379".getBytes()));
                    result[i] = RedisMessage.array(fields.toArray(new RedisMessage[0]));
                }
                return RedisMessage.array(result);
            }
            case "LEN" -> {
                return RedisMessage.integer(SlowLog.getInstance().getCount());
            }
            case "RESET" -> {
                SlowLog.getInstance().reset();
                return RedisMessage.simpleString("OK");
            }
            default -> {
                return RedisMessage.error("ERR unknown subcommand '" + subCommand + "'");
            }
        }
    }

    public RedisMessage handleClient(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return RedisMessage.error("ERR wrong number of arguments for 'client' command");
        }
        String subCommand = args.get(0).asString().toUpperCase();
        switch (subCommand) {
            case "SETNAME" -> {
                return RedisMessage.simpleString("OK");
            }
            case "GETNAME" -> {
                return RedisMessage.bulkString((byte[]) null);
            }
            case "LIST" -> {
                MetricsCollector mc = MetricsCollector.getInstance();
                long currentConns = mc.getCurrentConnections();
                return RedisMessage.bulkString(("id=1 addr=127.0.0.1:6379 " +
                        "fd=8 name= age=" + mc.getUptimeSeconds() + " " +
                        "idle=0 flags=N db=0 sub=0 psub=0 " +
                        "multi=-1 qbuf=0 qbuf-free=0 obl=0 oll=0 " +
                        "omem=0 events=r cmd=client\n").getBytes());
            }
            case "KILL" -> {
                return RedisMessage.error("ERR No such client");
            }
            default -> {
                return RedisMessage.error("ERR unknown subcommand '" + subCommand + "'");
            }
        }
    }

    public RedisMessage handleConfig(List<RedisMessage> args) {
        if (args.isEmpty()) {
            return RedisMessage.error("ERR wrong number of arguments for 'config' command");
        }
        String subCommand = args.get(0).asString().toUpperCase();
        switch (subCommand) {
            case "GET" -> {
                if (args.size() < 2) {
                    return RedisMessage.error("ERR wrong number of arguments for 'config|get' command");
                }
                String parameter = args.get(1).asString();
                SDCSConfig config = SDCSConfig.getInstance();
                if ("*".equals(parameter)) {
                    Map<String, String> allConfigs = config.getAllConfigs();
                    List<RedisMessage> result = new ArrayList<>();
                    for (Map.Entry<String, String> entry : allConfigs.entrySet()) {
                        result.add(RedisMessage.bulkString(entry.getKey().getBytes()));
                        result.add(RedisMessage.bulkString(entry.getValue() != null ? entry.getValue().getBytes() : null));
                    }
                    return RedisMessage.array(result);
                }
                String value = config.getConfig(parameter);
                if (value == null) {
                    return RedisMessage.array(new RedisMessage[0]);
                }
                return RedisMessage.array(
                        RedisMessage.bulkString(parameter.getBytes()),
                        RedisMessage.bulkString(value.getBytes())
                );
            }
            case "SET" -> {
                if (args.size() < 3) {
                    return RedisMessage.error("ERR wrong number of arguments for 'config|set' command");
                }
                String param = args.get(1).asString();
                String value = args.get(2).asString();
                if ("requirepass".equals(param)) {
                    SDCSConfig config = SDCSConfig.getInstance();
                    String currentPass = config.getRequirepass();
                    if (currentPass != null) {
                        if (args.size() < 4) {
                            return RedisMessage.error("ERR CONFIG SET requirepass requires old password when already set");
                        }
                        String oldPass = args.get(3).asString();
                        if (!currentPass.equals(oldPass)) {
                            return RedisMessage.error("ERR CONFIG SET requirepass old password does not match");
                        }
                    }
                }
                if (SDCSConfig.getInstance().setConfig(param, value)) {
                    return RedisMessage.simpleString("OK");
                }
                return RedisMessage.error("ERR Unsupported CONFIG parameter: " + param);
            }
            default -> {
                return RedisMessage.error("ERR Unknown or disabled command 'CONFIG'");
            }
        }
    }

    public RedisMessage handleCommand(List<RedisMessage> args) {
        return RedisMessage.array(new RedisMessage[0]);
    }

    public RedisMessage handleShutdown(List<RedisMessage> args) {
        String requirepass = SDCSConfig.getInstance().getRequirepass();
        boolean doSave = true;
        boolean confirmed = false;
        for (RedisMessage arg : args) {
            String opt = arg.asString().toUpperCase();
            if ("NOSAVE".equals(opt)) {
                doSave = false;
            } else if ("SAVE".equals(opt)) {
                doSave = true;
            } else if (requirepass != null && requirepass.equals(arg.asString())) {
                confirmed = true;
            } else {
                return RedisMessage.error("ERR Unknown or disabled command 'SHUTDOWN'");
            }
        }
        if (requirepass != null && !confirmed) {
            return RedisMessage.error("ERR SHUTDOWN requires password confirmation when requirepass is set");
        }
        final boolean save = doSave;
        NIOServer server = NIOServer.getInstance();
        if (server != null) {
            new Thread(() -> server.shutdown(save)).start();
        }
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleDebug(List<RedisMessage> args) {
        if (args.size() < 2) {
            return RedisMessage.error("ERR wrong number of arguments for 'debug' command");
        }
        String subCommand = args.get(0).asString().toUpperCase();
        switch (subCommand) {
            case "SET-ACTIVE-EXPIRE" -> {
                String val = args.get(1).asString();
                if (!"0".equals(val) && !"1".equals(val)) {
                    return RedisMessage.error("ERR wrong number of arguments for 'debug|set-active-expire' command");
                }
                return RedisMessage.simpleString("OK");
            }
            case "SLEEP" -> {
                long seconds = Long.parseLong(args.get(1).asString());
                if (seconds <= 0 || seconds > 30) {
                    return RedisMessage.error("ERR DEBUG SLEEP seconds must be between 1 and 30");
                }
                try {
                    Thread.sleep(seconds * 1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return RedisMessage.simpleString("OK");
            }
            default -> {
                return RedisMessage.error("ERR unknown subcommand '" + subCommand + "'");
            }
        }
    }

    public RedisMessage handleRole(List<RedisMessage> args) {
        // Replication is handled by proxy — always report as standalone master
        List<RedisMessage> result = new ArrayList<>();
        result.add(RedisMessage.bulkString("master".getBytes()));
        result.add(RedisMessage.integer(0));
        result.add(RedisMessage.array(new RedisMessage[0]));
        return RedisMessage.array(result);
    }

    public RedisMessage handleSlaveOf(List<RedisMessage> args) {
        // Replication is handled by proxy — just return OK
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handlePSync(List<RedisMessage> args) {
        // Replication is handled by proxy — respond as standalone master
        String replicationId = "837481b9d7146a2a6c4a7e2f8b3d1c5a9f0e6d3b";
        return RedisMessage.simpleString("FULLRESYNC " + replicationId + " 0");
    }

    public RedisMessage handleReplConf(List<RedisMessage> args) {
        // Replication is handled by proxy — just return OK
        return RedisMessage.simpleString("OK");
    }

    public RedisMessage handleTime(List<RedisMessage> args) {
        if (!args.isEmpty()) {
            return RedisMessage.error("wrong number of arguments for 'time' command");
        }
        long ms = System.currentTimeMillis();
        long seconds = ms / 1000;
        long microseconds = (ms % 1000) * 1000;
        return RedisMessage.array(
                RedisMessage.bulkString(String.valueOf(seconds).getBytes()),
                RedisMessage.bulkString(String.valueOf(microseconds).getBytes())
        );
    }

    public RedisMessage handleLastSave(List<RedisMessage> args) {
        return RedisMessage.integer(System.currentTimeMillis() / 1000);
    }

    public void registerCommands(Map<String, Function<List<RedisMessage>, RedisMessage>> registry) {
        registry.put("DBSIZE", this::handleDbSize);
        registry.put("SELECT", this::handleSelect);
        registry.put("FLUSHDB", this::handleFlushDb);
        registry.put("FLUSHALL", this::handleFlushAll);
        registry.put("INFO", this::handleInfo);
        registry.put("SAVE", this::handleSave);
        registry.put("BGSAVE", this::handleBgSave);
        registry.put("BGREWRITEAOF", this::handleBgRewriteAof);
        registry.put("SLOWLOG", this::handleSlowLog);
        registry.put("CLIENT", this::handleClient);
        registry.put("CONFIG", this::handleConfig);
        registry.put("COMMAND", this::handleCommand);
        registry.put("SHUTDOWN", this::handleShutdown);
        registry.put("DEBUG", this::handleDebug);
        registry.put("TIME", this::handleTime);
        registry.put("LASTSAVE", this::handleLastSave);
        registry.put("SLAVEOF", this::handleSlaveOf);
        registry.put("REPLICAOF", this::handleSlaveOf);
        registry.put("PSYNC", this::handlePSync);
        registry.put("REPLCONF", this::handleReplConf);
        registry.put("ROLE", this::handleRole);
    }
}
