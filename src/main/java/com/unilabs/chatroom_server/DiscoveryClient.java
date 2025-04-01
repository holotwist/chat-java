package com.unilabs.chatroom_server;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class DiscoveryClient {

    private final String discoveryServiceUrl;
    private final String serverUuid;
    private final String serverName;
    private final String serverHost; // The IP or Host that the clients will use to connect DIRECT
    private final int serverPort;
    private final String[] supportedMethods;
    private final HttpClient httpClient;
    private ScheduledExecutorService heartbeatScheduler;

    private static final long HEARTBEAT_INTERVAL_SECONDS = 30; // Send heartbeat every 30 sec (balance between verification and avoid saturating discovery)

    public DiscoveryClient(String discoveryServiceUrl, String serverUuid, String serverName, String serverHost, int serverPort, String[] supportedMethods) {
        // Make sure that the base URL does not end in / to join it well (does not affect functionality, only aesthetics)
        if (discoveryServiceUrl.endsWith("/")) {
            this.discoveryServiceUrl = discoveryServiceUrl.substring(0, discoveryServiceUrl.length() - 1);
        } else {
            this.discoveryServiceUrl = discoveryServiceUrl;
        }
        this.serverUuid = serverUuid;
        this.serverName = serverName;
        this.serverHost = serverHost; // Important: Must be reachable by clients, maybe later, more robust verification. For now, we have Relayed as fallback
        this.serverPort = serverPort;
        this.supportedMethods = supportedMethods;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public boolean registerServer() {
        JSONObject payload = new JSONObject();
        payload.put("uuid", serverUuid);
        payload.put("name", serverName);
        payload.put("host", serverHost);
        payload.put("port", serverPort);
        payload.put("supported_methods", new JSONArray(Arrays.asList(supportedMethods)));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(discoveryServiceUrl + "/register.php"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.println("Server registered successfully with Discovery Service.");
                return true;
            } else {
                System.err.println("Failed to register server. Status: " + response.statusCode() + ", Body: " + response.body());
                return false;
            }
        } catch (Exception e) {
            System.err.println("Error connecting to Discovery Service for registration: " + e.getMessage());
            return false;
        }
    }

    private void sendHeartbeat() {
        JSONObject payload = new JSONObject();
        payload.put("uuid", serverUuid);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(discoveryServiceUrl + "/heartbeat.php"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .timeout(Duration.ofSeconds(5))
                .build();
        try {
            // Send asynchronous so as not to block if the service takes time
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() != 200) {
                            System.err.println("Heartbeat failed. Status: " + response.statusCode() + ", Body: " + response.body());
                        } else {
                            // System.out.println("Heartbeat sent successfully."); // Optional: you can be very verbose, so as not to flood the terminal with text every heartbeat
                        }
                    }).exceptionally(e -> {
                        System.err.println("Error sending heartbeat: " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) { // Synchronous catch in case of initial sendAsync failure
            System.err.println("Error initiating heartbeat sending: " + e.getMessage());
        }
    }

    public void startHeartbeat() {
        if (heartbeatScheduler != null && !heartbeatScheduler.isShutdown()) {
            System.err.println("Heartbeat scheduler already running.");
            return;
        }
        // Use a dedicated 1-thread pool for heartbeat
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor();
        // Run immediately and then every N seconds (So that discovery does not exclude it from the list and fail the next heartbeat)
        heartbeatScheduler.scheduleAtFixedRate(this::sendHeartbeat, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("Heartbeat process started (every " + HEARTBEAT_INTERVAL_SECONDS + " seconds).");
    }

    public void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdown();
            try {
                // Wait a little while for pending tasks to be completed, avoid permanent blockages
                if (!heartbeatScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    heartbeatScheduler.shutdownNow();
                }
                System.out.println("Heartbeat process stopped.");
            } catch (InterruptedException e) {
                heartbeatScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
