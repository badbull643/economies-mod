package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * One message per line of JSON over a socket. Newline-delimited framing —
 * Gson escapes newlines inside strings, so a message can never span lines.
 */
public class MessageChannel implements AutoCloseable {

    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final Gson gson = new Gson();

    public MessageChannel(Socket socket) throws IOException {
        this.socket = socket;
        socket.setKeepAlive(true);
        this.in = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.out = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);

    }

    public synchronized void send(Message msg) {
        out.println(gson.toJson(msg));
    }

    private static final int MAX_LINE_LENGTH = 1_000_000;   // 1 MB — generous for a Sync batch

    public Message receive() throws IOException {
        String line = readLineCapped();
        while (line != null && line.trim().isEmpty()) {
            line = readLineCapped();
        }
        if (line == null) return null;

        JsonObject obj = new JsonParser().parse(line).getAsJsonObject();
        String type = obj.get("type").getAsString();
        return gson.fromJson(obj, classFor(type));
    }

    private String readLineCapped() throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') return sb.toString();
            if (c == '\r') continue;
            sb.append((char) c);
            if (sb.length() > MAX_LINE_LENGTH) {
                throw new IOException("message exceeded " + MAX_LINE_LENGTH + " bytes");
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private Class<? extends Message> classFor(String type) {
        switch (type) {
            case "Hello":    return Message.Hello.class;
            case "Propose":  return Message.Propose.class;
            case "Ping":     return Message.Ping.class;
            case "Sync":     return Message.Sync.class;
            case "Accepted": return Message.Accepted.class;
            case "Rejected": return Message.Rejected.class;
            case "Error":    return Message.Error.class;
            case "Pong":     return Message.Pong.class;
            case "QueryReply":  return Message.QueryReply.class;
            case "Query":       return Message.Query.class;
            case "MigrateRequest": return Message.MigrateRequest.class;
            case "MigrateResult":  return Message.MigrateResult.class;
            case "CatchUp":        return Message.CatchUp.class;
            case "CatchUpResult":  return Message.CatchUpResult.class;
            default: throw new IllegalStateException("Unknown message type: " + type);
        }
    }

    public String remoteAddress() {
        return socket.getRemoteSocketAddress().toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}