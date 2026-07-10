package com.qkinfotech.bizwax.sdcs.replication;

/**
 * Replication is handled by the proxy layer.
 * This class is kept only for backward compatibility — all commands
 * simply return OK without performing any replication logic.
 */
public class ReplicationManager {

    public enum Role { MASTER, SLAVE }

    private static final ReplicationManager INSTANCE = new ReplicationManager();
    private volatile Role role = Role.MASTER;

    private ReplicationManager() {}

    public static ReplicationManager getInstance() {
        return INSTANCE;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Role getRole() {
        return role;
    }

    public boolean isMaster() {
        return role == Role.MASTER;
    }
}
