package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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

    /**
     * Whether a write has failed on this channel.
     *
     * PrintWriter never throws — it catches IOException internally and raises a flag —
     * so a send to a socket whose peer has gone returns normally and silently does
     * nothing. Callers that wrap send in a try/catch are catching something that cannot
     * happen; this is the only way to notice.
     */
    public boolean failed() {
        return out.checkError();
    }

    static final int MAX_LINE_LENGTH = 1_000_000;   // 1 MB — generous for a Sync batch

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
            case "Attest":         return Message.Attest.class;
            case "CatchUp":        return Message.CatchUp.class;
            case "CatchUpResult":  return Message.CatchUpResult.class;
            default: throw new IllegalStateException("Unknown message type: " + type);
        }
    }

    // ── bulk transfers ──

    /**
     * Byte budget for one frame's worth of raw log lines.
     *
     * Comfortably under MAX_LINE_LENGTH, leaving headroom for the JSON escaping each
     * raw log line picks up when it's nested inside a message as a string. Every bulk
     * path — Sync, CatchUp, MigrateRequest — has a history longer than one frame as
     * its expected case eventually, so all three chunk through here.
     */
    static final int CHUNK_BUDGET_BYTES = 700_000;

    /**
     * Splits lines into chunks that each stay under a byte budget. Always returns at
     * least one chunk (possibly empty), so an empty input still sends one complete
     * message rather than nothing.
     */
    static List<List<String>> chunkByByteBudget(List<String> lines, int budget) {
        List<List<String>> chunks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        long currentBytes = 0;

        if (lines != null) {
            for (String line : lines) {
                int lineBytes = line.getBytes(StandardCharsets.UTF_8).length;
                if (!current.isEmpty() && currentBytes + lineBytes > budget) {
                    chunks.add(current);
                    current = new ArrayList<>();
                    currentBytes = 0;
                }
                current.add(line);
                currentBytes += lineBytes;
            }
        }
        chunks.add(current);
        return chunks;
    }

    static List<List<String>> chunkByByteBudget(List<String> lines) {
        return chunkByByteBudget(lines, CHUNK_BUDGET_BYTES);
    }

    public String remoteAddress() {
        return socket.getRemoteSocketAddress().toString();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}