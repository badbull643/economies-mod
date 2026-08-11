package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.PeerCache;

import java.util.List;

public abstract class Message {
    public String type;

    // ─── Client → Server ───

    public static class Hello extends Message {
        public String userId;
        public String displayName;   // so the host can label this peer
        public int hostPort;         // the port this peer would host on, not the ephemeral one
        public String publicKey;
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
        public List<PeerCache.Peer> knownPeers;
        public String hostUserId;
        public String hostName;
        public int hostPort;
        public String hostPublicKey;
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

    /** Lightweight liveness/status probe. No handshake, no state. */
    public static class Query extends Message {
        public String protocolVersion;
        public String nonce;
        public Query() { type = "Query"; }
    }

    public static class QueryReply extends Message {
        public boolean hosting;
        public String userId;
        public String hostName;
        public long lastSeq;
        public String lastHash;
        public int clientCount;
        public String protocolVersion;
        public String publicKey;      // so the client can verify without prior knowledge
        public String signature;      // over the canonical payload below
        public QueryReply() { type = "QueryReply"; }
    }

}