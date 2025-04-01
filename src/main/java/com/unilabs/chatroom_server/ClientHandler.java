package com.unilabs.chatroom_server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;

public class ClientHandler implements Runnable {

    private final Socket socket;
    private final ChatServer server;
    private PrintWriter writer;
    private BufferedReader reader;
    private ClientInfo clientInfo; // To be established after the handshake

    public ClientHandler(Socket socket, ChatServer server) {
        this.socket = socket;
        this.server = server;
    }

    @Override
    public void run() {
        String clientUuid = null;
        String nickname = null;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true); // true for autoFlush

            // 1. Handshake: Read UUID and Nickname
            clientUuid = reader.readLine();
            nickname = reader.readLine();

            if (clientUuid == null || clientUuid.trim().isEmpty() || nickname == null || nickname.trim().isEmpty()) {
                System.err.println("Invalid handshake from " + socket.getRemoteSocketAddress() + ". Closing connection.");
                // Optional: send error message before closing
                try { writer.println("ERROR: Invalid handshake (UUID or Nickname missing/empty)."); } catch (Exception ignored) {}
                return; // Exit run() closes the thread
            }

            clientUuid = clientUuid.trim();
            nickname = nickname.trim();

            // Validate if the UUID or Nickname is already in use (optional but good practice for future maintenance)
            if (server.isNicknameTaken(nickname) || server.isClientConnected(clientUuid)) {
                System.err.println("Handshake failed: Nickname '" + nickname + "' or UUID '" + clientUuid + "' already in use.");
                try { writer.println("ERROR: Nickname or UUID already in use."); } catch (Exception ignored) {}
                return;
            }


            // 2. Handshake OK: Send confirmation and register client
            writer.println("OK"); // Confirmation to the client
            this.clientInfo = new ClientInfo(clientUuid, nickname, socket, writer);
            server.addClient(this.clientInfo);

            // 3. Message reading loop
            String message;
            while ((message = reader.readLine()) != null) {
                // Validate/Sanitize message if necessary
                if (!message.trim().isEmpty()) {
                    server.broadcastMessage(this.clientInfo, message);
                }
            }

        // Common cases of connection failures are tested here
        } catch (SocketException e) {
            // Usually occurs when the client closes the connection abruptly
            System.out.println("Client " + (nickname != null ? nickname : "UNKNOWN") + " disconnected (SocketException): " + e.getMessage());
        } catch (IOException e) {
            System.err.println("IOException for client " + (nickname != null ? nickname : "UNKNOWN") + ": " + e.getMessage());
            // e.printStackTrace(); // Uncomment for debug
        } catch (Exception e) {
            System.err.println("Unexpected error in ClientHandler for " + (nickname != null ? nickname : "UNKNOWN") + ": " + e.getMessage());
            e.printStackTrace(); // Print for unexpected errors
        } finally {
            // 4. Cleanup: Ensure that the client is removed from the server.
            if (clientInfo != null) {
                server.removeClient(clientInfo.getUuid(), "Connection closed");
            } else if (clientUuid != null) {
                // If it failed after reading UUID but before fully creating ClientInfo
                server.removeClient(clientUuid, "Handshake failed or incomplete");
            }
            // Resources (socket, reader, writer) are closed by removeClient via clientInfo.closeDirectConnection()
            // Or if clientInfo was never created, the implicit try-with-resources or the finally here should close the initial socket
            // Let's make sure to close the socket if clientInfo was not created, so as not to leave orphaned sockets and threads
            if (clientInfo == null) {
                try {
                    if (writer != null) writer.close();
                    if (reader != null) reader.close();
                    if (socket != null && !socket.isClosed()) socket.close();
                } catch (IOException ex) {
                    System.err.println("Error closing socket during cleanup for failed handshake: " + ex.getMessage());
                }
            }

        }
    }

    // Method to send a message to THIS client (used by the server's broadcast)
    public void sendMessage(String message) {
        if (writer != null) {
            writer.println(message);
            // We don't need to check writer.checkError() always, but it could be useful, it looks like TODO
        }
    }
}
