package com.qkinfotech.bizwax.sdcs;

import com.qkinfotech.bizwax.sdcs.config.SDCSConfig;
import com.qkinfotech.bizwax.sdcs.metrics.PrometheusExporter;
import com.qkinfotech.bizwax.sdcs.metrics.SDCSMonitor;
import com.qkinfotech.bizwax.sdcs.registry.RegistryClient;
import com.qkinfotech.bizwax.sdcs.server.NIOServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SDCSServer {

    private static final Logger logger = LoggerFactory.getLogger(SDCSServer.class);

    private final NIOServer server;
    private final SDCSConfig config;
    private PrometheusExporter prometheusExporter;
    private RegistryClient registryClient;

    public SDCSServer(SDCSConfig config) {
        this.config = config;
        this.server = new NIOServer(config);
    }

    public void start() {
        try {
            SDCSMonitor.getInstance();
            if (config.isMetricsEnabled()) {
                prometheusExporter = new PrometheusExporter(config.getMetricsPort());
                prometheusExporter.start();
            }
            server.start();
            registryClient = new RegistryClient(config);
            registryClient.start();
            logger.info("SDCS started on port {}", config.getPort());
        } catch (Exception e) {
            logger.error("Failed to start SDCS: {}", e.getMessage(), e);
        }
    }

    public void stop() {
        if (registryClient != null) {
            registryClient.stop();
        }
        server.stop();
        if (prometheusExporter != null) {
            prometheusExporter.stop();
        }
        try {
            com.qkinfotech.bizwax.sdcs.server.SDCSCommandExecutor.getPersistenceManager().stop();
        } catch (Exception e) {
            logger.error("Error stopping persistence: {}", e.getMessage());
        }
        logger.info("SDCS stopped");
    }

    public static void main(String[] args) {
        SDCSConfig config = new SDCSConfig();
        config.parseArgs(args);

        if (config.isHelpRequested()) {
            return;
        }

        SDCSServer server = new SDCSServer(config);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "SDCS-ShutdownHook"));
    }
}
