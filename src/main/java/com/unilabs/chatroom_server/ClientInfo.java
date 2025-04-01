package com.unilabs.chatroom_server;

import java.io.PrintWriter;
import java.net.Socket;

public class ClientInfo {
    private final String uuid;
    private final String nickname;
    private final ClientType type;
    private PrintWriter writer; // DIRECT clients only
    private Socket socket;      // DIRECT clients only
    private long lastHeardFromTimestamp; // For RELAY timeouts

    // Direct Client constructor
    public ClientInfo(String uuid, String nickname, Socket socket, PrintWriter writer) {
        this.uuid = uuid;
        this.nickname = nickname;
        this.type = ClientType.DIRECT;
        this.socket = socket;
        this.writer = writer;
        this.lastHeardFromTimestamp = System.currentTimeMillis(); // Initial
    }

    // Relay Client constructor
    public ClientInfo(String uuid, String nickname) {
        this.uuid = uuid;
        this.nickname = nickname;
        this.type = ClientType.RELAY;
        this.socket = null; // Not applicable
        this.writer = null; // Not applicable
        this.lastHeardFromTimestamp = System.currentTimeMillis(); // Initial
    }

    public String getUuid() { return uuid; }
    public String getNickname() { return nickname; }
    public ClientType getType() { return type; }
    public PrintWriter getWriter() { return writer; }
    public Socket getSocket() { return socket; }
    public long getLastHeardFromTimestamp() { return lastHeardFromTimestamp; }

    public void updateLastHeardFrom() {
        this.lastHeardFromTimestamp = System.currentTimeMillis();
    }

    // Closes resources if it's a direct client
    public void closeDirectConnection() {
        if (type == ClientType.DIRECT) {
            try {
                if (writer != null) writer.close();
                if (socket != null && !socket.isClosed()) socket.close();
            } catch (Exception e) {
                System.err.println("Error closing resources for client " + nickname + ": " + e.getMessage());
            }
        }
    }

    @Override
    public String toString() {
        return "ClientInfo{" +
                "uuid='" + uuid + '\'' +
                ", nickname='" + nickname + '\'' +
                ", type=" + type +
                '}';
    }
}