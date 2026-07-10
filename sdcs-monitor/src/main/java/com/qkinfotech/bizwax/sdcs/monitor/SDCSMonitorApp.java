package com.qkinfotech.bizwax.sdcs.monitor;

import com.qkinfotech.bizwax.sdcs.monitor.config.MonitorConfig;
import com.qkinfotech.bizwax.sdcs.monitor.http.ClusterHandler;
import com.qkinfotech.bizwax.sdcs.monitor.registry.RegistryReader;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

public class SDCSMonitorApp {

    private static final Logger log = LoggerFactory.getLogger(SDCSMonitorApp.class);

    private final MonitorConfig config;
    private final RegistryReader registryReader;
    private HttpServer httpServer;

    public SDCSMonitorApp(String configPath) {
        this.config = new MonitorConfig(configPath);
        this.registryReader = new RegistryReader(config);
    }

    public void start() throws Exception {
        log.info("Starting SDCS Monitor on {}:{}", config.getHttpBind(), config.getHttpPort());

        registryReader.init();

        httpServer = HttpServer.create(new InetSocketAddress(config.getHttpBind(), config.getHttpPort()), 0);
        httpServer.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        httpServer.createContext("/health", new ClusterHandler.HealthHandler());
        httpServer.createContext("/cluster", new ClusterHandler(registryReader, config));
        httpServer.createContext("/proxies", new ClusterHandler.ProxyHandler(registryReader));
        httpServer.createContext("/", new StaticFileHandler());

        httpServer.start();
        log.info("SDCS Monitor started on port {}", config.getHttpPort());

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down SDCS Monitor...");
            stop();
            log.info("SDCS Monitor stopped.");
        }));
    }

    public void stop() {
        if (httpServer != null) {
            httpServer.stop(1);
        }
        registryReader.close();
    }

    public void await() {
        Thread.currentThread().setName("main-wait");
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "monitor.properties";
        try {
            new SDCSMonitorApp(configPath).start();
        } catch (Exception e) {
            System.err.println("Failed to start SDCS Monitor: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Serves the frontend HTML page from classpath resources.
     */
    static class StaticFileHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if ("/".equals(path) || "/index.html".equals(path)) {
                byte[] html = loadResource("/static/index.html");
                if (html != null) {
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
                    exchange.sendResponseHeaders(200, html.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(html); }
                    return;
                }
            }
            // 404 for unknown paths
            byte[] notFound = "<h1>404 Not Found</h1>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, notFound.length);
            try (OutputStream os = exchange.getResponseBody()) { os.write(notFound); }
        }

        private byte[] loadResource(String resourcePath) {
            try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
                if (in == null) return null;
                return in.readAllBytes();
            } catch (Exception e) {
                return null;
            }
        }
    }
}
