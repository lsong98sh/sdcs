package com.qkinfotech.bizwax.sdcs.metrics;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class MonitorManager {

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(MonitorManager.class);
    private static final MonitorManager INSTANCE = new MonitorManager();
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    private MonitorManager() {}

    public static MonitorManager getInstance() {
        return INSTANCE;
    }

    public void addListener(Consumer<String> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<String> listener) {
        listeners.remove(listener);
    }

    public boolean hasListeners() {
        return !listeners.isEmpty();
    }

    public void broadcast(String commandLine) {
        if (listeners.isEmpty()) return;
        String timestamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS")
                .format(new java.util.Date());
        String monitorOutput = timestamp + " [" + Thread.currentThread().getName() + "] " + commandLine;
        for (Consumer<String> listener : listeners) {
            try {
                listener.accept(monitorOutput);
            } catch (Exception e) {
                logger.warn("Removing monitor listener due to exception: {}", e.getMessage(), e);
                listeners.remove(listener);
            }
        }
    }
}
