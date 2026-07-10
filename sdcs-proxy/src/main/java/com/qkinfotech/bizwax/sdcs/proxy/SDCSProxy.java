package com.qkinfotech.bizwax.sdcs.proxy;

import com.qkinfotech.bizwax.sdcs.proxy.config.ProxyConfig;
import com.qkinfotech.bizwax.sdcs.proxy.registry.RegistryManager;
import com.qkinfotech.bizwax.sdcs.proxy.server.ProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SDCSProxy {

    private static final Logger log = LoggerFactory.getLogger(SDCSProxy.class);

    private final ProxyConfig config;
    private final RegistryManager registryManager;
    private final ProxyServer proxyServer;

    public SDCSProxy(String configPath) {
        this.config = new ProxyConfig(configPath);
        this.registryManager = new RegistryManager(config);
        this.proxyServer = new ProxyServer(config, registryManager);
    }

    public void start() throws Exception {
        log.info("Starting SDCS Proxy on {}:{}", config.getProxyBind(), config.getProxyPort());

        registryManager.start();
        proxyServer.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down SDCS Proxy...");
            try {
                proxyServer.stop();
                registryManager.stop();
            } catch (Exception e) {
                log.error("Error during shutdown", e);
            }
            log.info("SDCS Proxy stopped.");
        }));

        log.info("SDCS Proxy started successfully on port {}", config.getProxyPort());
    }

    public void await() throws InterruptedException {
        proxyServer.await();
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "config.properties";
        try {
            new SDCSProxy(configPath).start();
        } catch (Exception e) {
            System.err.println("Failed to start SDCS Proxy: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
