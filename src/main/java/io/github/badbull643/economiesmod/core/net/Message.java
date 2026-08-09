package io.github.badbull643.economiesmod.core.net;

import java.util.List;

public abstract class Message {
    public String type;

    // ─── Client → Server ───

    public static class Hello extends Message {
        public String userId;
        public String publicKey;      // base64
        public long lastSeq;
        public String lastHash;
        public String protocolVersion;
        public Hello() { type = "Hello"; }
    }

    public static class Propose extends Message {
        public String clientEventId;
        public String eventType;
        public String eventJson;
        public String signature;      // base64, over canonicalPayload(event)
        public Propose() { type = "Propose"; }
    }

    public static class Ping extends Message {
        public Ping() { type = "Ping"; }
    }

    // ─── Server → Client ───

    public static class Sync extends Message {
        public List<String> logLines;
        public boolean complete;
        public Sync() { type = "Sync"; }
    }

    public static class Accepted extends Message {
        public String logLine;
        public Accepted() { type = "Accepted"; }
    }

    public static class Rejected extends Message {
        public String clientEventId;
        public String reason;
        public Rejected() { type = "Rejected"; }
    }

    public static class Error extends Message {
        public String reason;
        public Error() { type = "Error"; }
    }

    public static class Pong extends Message {
        public Pong() { type = "Pong"; }
    }

    public static class SteppingDown extends Message {
        public long finalSeq;
        public SteppingDown() { type = "SteppingDown"; }
    }
}