package com.unilabs.chatroom_server;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Class that represent the main entry point
 * Needs refactor
 */
public class ChatServer {

    private final int port;
    private final String serverName;
    private final String serverUuid;
    private final String discoveryUrl;
    private final String relayUrl;
    private final String publicHost; // IP/Host to be announced

    private ServerSocket serverSocket;
    private final ExecutorService clientExecutorPool; // Thread pool for direct ClientHandlers
    private final Map<String, ClientInfo> connectedClients; // UUID map -> ClientInfo
    private DiscoveryClient discoveryClient;
    private RelayHandler relayHandler;
    private volatile boolean running = false; // To control the server loop

    // Constructor
    public ChatServer(int port, String serverName, String discoveryUrl, String relayUrl, String publicHost) {
        this.port = port;
        this.serverName = serverName;
        this.discoveryUrl = discoveryUrl;
        this.relayUrl = relayUrl;
        this.publicHost = publicHost; // Use the provided
        this.serverUuid = UUID.randomUUID().toString();
        this.connectedClients = new ConcurrentHashMap<>();
        // Usar un pool de hilos cacheado o fijo según se necesite
        this.clientExecutorPool = Executors.newCachedThreadPool();
    }

    public void start() {
        System.out.println("Starting Chat Server '" + serverName + "' (UUID: " + serverUuid + ") on port " + port + "...");
        running = true;

        // 1. Initialize Discovery and Relay Handlers
        // TODO: Decide which connection methods to support (direct, relay, both)
        String[] supportedMethods = {"direct", "relay"}; // HACK: we support both, while implementing it later
        discoveryClient = new DiscoveryClient(discoveryUrl, serverUuid, serverName, publicHost, port, supportedMethods);
        relayHandler = new RelayHandler(relayUrl, serverUuid, this);

        // 2. Register for the Discovery Service
        if (discoveryClient.registerServer()) {
            // 3. Start Heartbeat
            discoveryClient.startHeartbeat();
        } else {
            System.err.println("Failed to register with discovery service. Server may not be discoverable.");
            // Should it continue? For now, yes.
            // For fast Minimum Viable Product (MVP)
        }

        // 4. Start Relay Polling
        relayHandler.startPolling();

        // 5. Start listening for TCP connections in a separate thread
        new Thread(this::listenForConnections, "TCP-Listener-Thread").start();

        System.out.println("Server ready and listening on " + publicHost + ":" + port);
        System.out.println("Announced methods: " + String.join(", ", supportedMethods));
        System.out.println("Using Discovery: " + discoveryUrl);
        System.out.println("Using Relay: " + relayUrl);


        // Add a hook for orderly closing (Ctrl+C), avoid unexpected closing and orphaned threads
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "Shutdown-Hook"));
    }

    private void listenForConnections() {
        try {
            serverSocket = new ServerSocket(port);
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept(); // Blocking until a connection arrives
                    System.out.println("Accepted direct connection from: " + clientSocket.getRemoteSocketAddress());
                    // Create and run a ClientHandler for this client in the thread pool
                    clientExecutorPool.execute(new ClientHandler(clientSocket, this));
                } catch (IOException e) {
                    if (!running) {
                        System.out.println("Server socket closed, stopping listener.");
                        break; // Exiting the loop if the server is stopped
                    }
                    System.err.println("Error accepting client connection: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            if (running) { // Only show error if it was not an intentional shutdown
                System.err.println("Could not start server socket on port " + port + ": " + e.getMessage());
                stop(); // Attempting to stop everything if the main socket fails
            }
        } finally {
            closeServerSocket();
        }
    }

    // Called by ClientHandler after successful handshake
    public synchronized void addClient(ClientInfo clientInfo) {
        if (clientInfo == null || clientInfo.getUuid() == null) return;

        if (connectedClients.containsKey(clientInfo.getUuid())) {
            System.err.println("Attempted to add client with duplicate UUID: " + clientInfo.getUuid());
            // If direct, close the new connection
            if(clientInfo.getType() == ClientType.DIRECT) clientInfo.closeDirectConnection();
            return;
        }
        if (isNicknameTaken(clientInfo.getNickname())) {
            System.err.println("Attempted to add client with duplicate nickname: " + clientInfo.getNickname());
            // Enviar error y cerrar si es directo
            if(clientInfo.getType() == ClientType.DIRECT && clientInfo.getWriter() != null) {
                try { clientInfo.getWriter().println("ERROR: Nickname '" + clientInfo.getNickname() + "' is already taken."); } catch (Exception ignored) {}
                clientInfo.closeDirectConnection();
            }
            // If it's relay, we could send error message via relay, but it's more complex, and I'm too lazy to do it lmao
            // TODO
            return;
        }


        connectedClients.put(clientInfo.getUuid(), clientInfo);
        System.out.println(clientInfo.getNickname() + " (" + clientInfo.getType() + ") joined the chat. Total clients: " + connectedClients.size());
        broadcastSystemMessage(clientInfo.getNickname() + " has joined the chat.", clientInfo.getUuid()); // Notifying others that someone has joined the room
        // Optional: Send recent history or list of users to new client
        sendUserListToClient(clientInfo);
    }

    // Called by ClientHandler or RelayHandler Timeout
    public synchronized void removeClient(String uuid, String reason) {
        ClientInfo removedClient = connectedClients.remove(uuid);
        if (removedClient != null) {
            System.out.println(removedClient.getNickname() + " (" + removedClient.getType() + ") left the chat (" + reason + "). Total clients: " + connectedClients.size());
            broadcastSystemMessage(removedClient.getNickname() + " has left the chat. (" + reason + ")", uuid);

            // Close the connection if it is direct
            if (removedClient.getType() == ClientType.DIRECT) {
                removedClient.closeDirectConnection();
            }
        }
        // If the UUID was missing, do nothing
    }

    // Relays a message from one client to all other clients, ensuring that all participants see it
    public void broadcastMessage(ClientInfo senderInfo, String message) {
        if (senderInfo == null) return; // Do not send messages of unknown origin
        String formattedMessage = "[" + senderInfo.getNickname() + "]: " + message;
        System.out.println("Broadcasting: " + formattedMessage); // Log on the server

        // Update the sender's timestamp if it is relayed
        if (senderInfo.getType() == ClientType.RELAY) {
            senderInfo.updateLastHeardFrom();
        }

        broadcast(formattedMessage, senderInfo.getUuid());
    }

    // Sends a system message (join/leave) to all (or to all but one)
    public void broadcastSystemMessage(String message, String excludeUuid) {
        String formattedMessage = "[SERVER]: " + message;
        System.out.println("System Msg: " + formattedMessage); // Log on the server
        broadcast(formattedMessage, excludeUuid);
    }

    // Central broadcasting logic
    private void broadcast(String message, String excludeUuid) {
        connectedClients.forEach((uuid, client) -> {
            if (!uuid.equals(excludeUuid)) { // Do not send to the original sender
                try {
                    if (client.getType() == ClientType.DIRECT) {
                        client.getWriter().println(message);
                    } else { // client.getType() == ClientType.RELAY
                        relayHandler.sendMessageToRelay(client.getUuid(), message, "chat");
                    }
                } catch (Exception e) {
                    // If direct sending fails, assume disconnection (Avoid undeclared behavior)
                    if (client.getType() == ClientType.DIRECT) {
                        System.err.println("Error sending message to direct client " + client.getNickname() + ". Removing. Error: " + e.getMessage());
                        // Removal must occur outside the iterator to avoid ConcurrentModificationException
                        // You can mark to remove later or use removeClient which is synchronized.
                        // For simplicity now, we'll call removeClient here, but it's a potential risk
                        // removeClient(client.getUuid(), “Send Error”); // BEWARE OF CONCURRENT MODIFICATION, whoever uncomments this line.
                        // Better solution: use an explicit iterator and call iterator.remove() or collect UUIDs to remove.
                        // Or rely on the ClientHandler to detect the error and call removeClient.
                        // For now, it's a HACK
                    } else {
                        System.err.println("Error queueing message via relay for " + client.getNickname() + ": " + e.getMessage());
                        // We cannot assume relay disconnection so easily, since polling generates delay
                    }
                }
            }
        });
    }

    // Called by RelayHandler when a message arrives through the relay
    public void handleRelayMessage(String senderUuid, String messageBody, String messageType) {
        System.out.println("Received relay message from " + senderUuid + " (Type: " + messageType + ")"); // Log

        if ("control".equalsIgnoreCase(messageType)) {
            // Processing control messages (e.g. Handshake)
            try {
                // We assume that the message body is JSON for control (maybe in the future add robust verification).
                JSONObject controlMsg = new JSONObject(messageBody);
                String action = controlMsg.optString("action");
                String nickname = controlMsg.optString("nickname");

                if ("HANDSHAKE_REQUEST".equalsIgnoreCase(action) && nickname != null && !nickname.isEmpty()) {
                    if (isClientConnected(senderUuid) || isNicknameTaken(nickname)) {
                        System.err.println("Relay handshake failed: UUID or Nickname already exists (" + senderUuid + ", " + nickname + ")");
                        // Send error response via relay
                        JSONObject errorResponse = new JSONObject();
                        errorResponse.put("action", "HANDSHAKE_ERROR");
                        errorResponse.put("reason", "UUID or Nickname already taken.");
                        relayHandler.sendMessageToRelay(senderUuid, errorResponse.toString(), "control");
                        return;
                    }

                    System.out.println("Processing Relay Handshake from: " + senderUuid + " Nick: " + nickname);
                    ClientInfo relayClient = new ClientInfo(senderUuid, nickname);
                    addClient(relayClient); // Add to the list and notify others

                    // Send handshake confirmation via relay
                    JSONObject response = new JSONObject();
                    response.put("action", "HANDSHAKE_OK");
                    relayHandler.sendMessageToRelay(senderUuid, response.toString(), "control");
                } else {
                    System.err.println("Unknown or invalid control message via relay: " + messageBody);
                }

            } catch (Exception e) {
                System.err.println("Error parsing relay control message: " + e.getMessage() + "\nBody: " + messageBody);
            }

        } else { // Assume default 'chat' type
            ClientInfo senderInfo = connectedClients.get(senderUuid);
            if (senderInfo != null && senderInfo.getType() == ClientType.RELAY) {
                senderInfo.updateLastHeardFrom(); // Update timestamp
                // This is a chat message from a known relay client
                broadcastMessage(senderInfo, messageBody);
            } else {
                System.err.println("Received relay chat message from unknown or non-relay UUID: " + senderUuid);
                // Ignore or send error? Ignore for now. For simplicity.
            }
        }
    }

    // Send list of users to a specific client
    private void sendUserListToClient(ClientInfo recipient) {
        JSONArray users = new JSONArray();
        connectedClients.values().forEach(c -> {
            JSONObject user = new JSONObject();
            user.put("nickname", c.getNickname());
            user.put("type", c.getType().name());
            users.put(user);
        });

        JSONObject userListMsg = new JSONObject();
        userListMsg.put("type", "userlist");
        userListMsg.put("users", users);

        String message = "[SERVER]: Users currently online: " + users.length(); // Simple message
        String detailedMessage = userListMsg.toString(); // JSON message for client parsing


        try {
            if (recipient.getType() == ClientType.DIRECT) {
                recipient.getWriter().println(message); // Send simple version
                // recipient.getWriter().println(detailedMessage); // Or send JSON, only one method at a time, one must be commented, to avoid problems (Double Send ERREXCH).
            } else {
                relayHandler.sendMessageToRelay(recipient.getUuid(), message, "system"); // Send simple version
                // relayHandler.sendMessageToRelay(recipient.getUuid(), detailedMessage, "system"); // Or send JSON (ERREXCH)
            }
        } catch (Exception e) {
            System.err.println("Error sending user list to " + recipient.getNickname() + ": " + e.getMessage());
        }
    }


    // --- Utility methods --- //
    // Here are all the methods for the main inputs

    public boolean isNicknameTaken(String nickname) {
        if (nickname == null || nickname.trim().isEmpty()) return false;
        return connectedClients.values().stream()
                .anyMatch(c -> nickname.equalsIgnoreCase(c.getNickname()));
    }

    public boolean isClientConnected(String uuid) {
        if (uuid == null) return false;
        return connectedClients.containsKey(uuid);
    }

    public Map<String, ClientInfo> getConnectedClients() {
        return connectedClients; // Return the map (it is concurrent)
    }


    // --- Server Shutdown --- //
    // Orderly shutdown to ensure correct shutdown of threads

    public void stop() {
        if (!running) return; // Avoid multiple shutdown
        running = false;
        System.out.println("\nShutting down server...");

        // 1. Stop accepting new connections
        closeServerSocket();

        // 2. Stop Heartbeat and Polling
        if (discoveryClient != null) discoveryClient.stopHeartbeat();
        if (relayHandler != null) relayHandler.stopPolling();

        // 3. Notify and disconnect clients (optional but polite, informs users that the room is over)
        broadcastSystemMessage("Server is shutting down.", null);
        // Allow a small margin for relay messages to be sent, this ensures that clients are reached
        try { TimeUnit.SECONDS.sleep(1); } catch (InterruptedException ignored) {}

        // Close direct connections and clear map
        connectedClients.values().forEach(client -> {
            if (client.getType() == ClientType.DIRECT) {
                client.closeDirectConnection();
            }
        });
        connectedClients.clear(); // Clean the map after

        // 4. Stop client thread pooling
        clientExecutorPool.shutdown();
        try {
            if (!clientExecutorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                clientExecutorPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            clientExecutorPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // 5. Attempt to de-register (optional, best-effort)
        // if (discoveryClient != null) discoveryClient.deregister(); // You need to implement de-register, it will be for then TODO #003A

        System.out.println("Server shutdown complete.");
    }

    private void closeServerSocket() {
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                System.err.println("Error closing server socket: " + e.getMessage()); // Debugging if orphaned sockets remain
            }
        }
    }


    // --- Main Method --- //
    public static void main(String[] args) {
        // Configuration (best from args or config file, for now hardset with possibility of arguments)
        int port = 5001; // TCP listening port
        String name = "Sala top globales"; // TODO: Implement ability to rename from commands or arguments
        // Here we use RaquelAPI's service
        String discovery = "https://raquelcloud.x10host.com/api/chatroom-19837/discovery"; // Base URL
        String relay = "https://raquelcloud.x10host.com/api/chatroom-19837/relay";       // Base URL
        String publicHost = null; // Public IP or DNS name that clients will use for DIRECT connection

        // Try to detect local IP (may not be correct if behind NAT)
        try {
            publicHost = InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            System.err.println("Warning: Could not auto-detect local host address. Using 127.0.0.1.");
            publicHost = "127.0.0.1"; // Fallback, we assume localhost
        }

        // Overwrite with command line arguments if provided
        // Example: java com.unilabs.chatroom_server.ChatServer 5001 "My Room" http://discover... http://relay... my.public.ip
        // java -jar file.jar [ARGS]
        if (args.length >= 1) port = Integer.parseInt(args[0]);
        if (args.length >= 2) name = args[1];
        if (args.length >= 3) discovery = args[2];
        if (args.length >= 4) relay = args[3];
        if (args.length >= 5) publicHost = args[4]; // Allow to specify explicitly, with arguments


        ChatServer server = new ChatServer(port, name, discovery, relay, publicHost);
        server.start();

        // The server will continue to run until it stops (Ctrl+C or error).
        // The ShutdownHook will take care of the cleanup. Best practices when handling concurrency.
    }
}
