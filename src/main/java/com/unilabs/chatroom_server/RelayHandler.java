package com.unilabs.chatroom_server;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class RelayHandler {

    private final String relayServiceUrl;
    private final String serverUuid;
    private final ChatServer chatServer; // Reference for passing received messages
    private final HttpClient httpClient;
    private ScheduledExecutorService pollingScheduler;
    private ScheduledExecutorService cleanupScheduler;


    private static final long POLLING_INTERVAL_SECONDS = 3; // Consult every 3 sec
    private static final long RELAY_CLIENT_TIMEOUT_MS = 60 * 1000; // 60 seconds without hearing from the relay client (if the client does not respond with messages, it causes false positive)
    // TODO: heartbeat also from the client to avoid ejection due to inactivity, although it may be a functionality

    public RelayHandler(String relayServiceUrl, String serverUuid, ChatServer chatServer) {
        if (relayServiceUrl.endsWith("/")) { // Aesthetics, avoid poorly formatted URLs
            this.relayServiceUrl = relayServiceUrl.substring(0, relayServiceUrl.length() - 1);
        } else {
            this.relayServiceUrl = relayServiceUrl;
        }
        this.serverUuid = serverUuid;
        this.chatServer = chatServer;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private void pollForMessages() {
        String encodedUuid = URLEncoder.encode(serverUuid, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relayServiceUrl + "/get_messages.php?recipient=" + encodedUuid))
                .GET()
                .timeout(Duration.ofSeconds(POLLING_INTERVAL_SECONDS + 2)) // Timeout > poll interval
                .build();

        try {
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() == 200) {
                            String body = response.body();
                            if (body != null && !body.trim().isEmpty() && !body.trim().equals("[]")) {
                                try {
                                    JSONArray messages = new JSONArray(body);
                                    for (int i = 0; i < messages.length(); i++) {
                                        JSONObject msg = messages.getJSONObject(i);
                                        chatServer.handleRelayMessage(
                                                msg.getString("sender"), // sender_uuid
                                                msg.getString("message"), // message_body
                                                msg.optString("type", "chat") // message_type
                                        );
                                    }
                                } catch (Exception e) {
                                    System.err.println("Error parsing messages from relay: " + e.getMessage() + "\nBody: " + body);
                                }
                            }
                            // If returns [] or empty, do nothing
                        } else {
                            System.err.println("Relay polling failed. Status: " + response.statusCode() + ", Body: " + response.body());
                        }
                    }).exceptionally(e -> {
                        System.err.println("Error polling relay service: " + e.getCause()); // getCause to see the actual exception
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("Error initiating relay polling: " + e.getMessage());
        }
    }

    public void sendMessageToRelay(String recipientUuid, String message, String type) {
        JSONObject payload = new JSONObject();
        payload.put("sender", serverUuid);
        payload.put("recipient", recipientUuid);
        payload.put("message", message);
        payload.put("type", type);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(relayServiceUrl + "/send_message.php"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .timeout(Duration.ofSeconds(5))
                .build();

        try {
            // Use synchronous here may be better to ensure sending before proceeding
            // Or use sendAsync and handle the response if it's needed to know if it failed
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .thenAccept(response -> {
                        if (response.statusCode() != 202) { // Wait for 202 Accepted
                            System.err.println("Failed to send message via relay to " + recipientUuid + ". Status: " + response.statusCode() + ", Body: " + response.body());
                        }
                    }).exceptionally(e -> {
                        System.err.println("Error sending message via relay to " + recipientUuid + ": " + e.getMessage());
                        return null;
                    });
        } catch (Exception e) {
            System.err.println("Error initiating relay send: " + e.getMessage());
        }
    }

    private void checkRelayClientTimeouts() {
        long now = System.currentTimeMillis();
        chatServer.getConnectedClients().values().forEach(client -> {
            if (client.getType() == ClientType.RELAY) {
                if ((now - client.getLastHeardFromTimestamp()) > RELAY_CLIENT_TIMEOUT_MS) {
                    System.out.println("Relay client " + client.getNickname() + " (" + client.getUuid() + ") timed out. Removing.");
                    // The removeClient will take care of the notification and cleaning logic
                    chatServer.removeClient(client.getUuid(), "Relay Timeout");
                }
            }
        });
    }


    public void startPolling() {
        if (pollingScheduler != null && !pollingScheduler.isShutdown()) {
            System.err.println("Relay polling scheduler already running.");
            return;
        }
        pollingScheduler = Executors.newSingleThreadScheduledExecutor();
        pollingScheduler.scheduleAtFixedRate(this::pollForMessages, 0, POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS);
        System.out.println("Relay polling process started (every " + POLLING_INTERVAL_SECONDS + " seconds).");

        // Start also timeout check for relay clients
        if (cleanupScheduler == null || cleanupScheduler.isShutdown()) {
            cleanupScheduler = Executors.newSingleThreadScheduledExecutor();
            // Check timeouts every minute, e.g.
            cleanupScheduler.scheduleAtFixedRate(this::checkRelayClientTimeouts, 60, 60, TimeUnit.SECONDS);
            System.out.println("Relay client timeout check started.");
        }
    }

    public void stopPolling() {
        if (pollingScheduler != null) {
            pollingScheduler.shutdown();
            try {
                if (!pollingScheduler.awaitTermination(5, TimeUnit.SECONDS)) pollingScheduler.shutdownNow();
                System.out.println("Relay polling process stopped.");
            } catch (InterruptedException e) { pollingScheduler.shutdownNow(); Thread.currentThread().interrupt(); }
        }
        if (cleanupScheduler != null) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) cleanupScheduler.shutdownNow();
                System.out.println("Relay client timeout check stopped.");
            } catch (InterruptedException e) { cleanupScheduler.shutdownNow(); Thread.currentThread().interrupt(); }
        }
    }
}
