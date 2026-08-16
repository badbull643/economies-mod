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
        public String marketId;      // null means "no history yet" — free to adopt any market
        public String marketName;    // so a refusal can name both markets, not just their UUIDs
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

    /**
     * The history the client is missing.
     *
     * Chunked: a fresh joiner syncs from seq 1 and so pulls the whole market, which
     * outgrows one frame long before any other bulk path does. Only the first frame
     * carries the host identity and peer fields; the rest carry logLines alone.
     */
    public static class Sync extends Message {
        public List<String> logLines;
        /** False on every chunk but the last. */
        public boolean complete = true;
        public List<PeerCache.Peer> knownPeers;
        public String hostUserId;
        public String hostName;
        public int hostPort;
        public String hostPublicKey;
        public String marketId;
        public String marketName;
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
        /** Machine-readable refusal kind, so the client can offer the right fix
         *  instead of just echoing prose at the user. See HostServer.Refusal. */
        public String code;
        /** Where this host actually is. Sent with AHEAD so the client can work out
         *  whether it merely extends the host's history or has diverged from it —
         *  the host can't tell, because Hello only carries the client's own head. */
        public long hostSeq;
        public String hostHash;
        public Error() { type = "Error"; }
    }

    public static class Pong extends Message {
        public Pong() { type = "Pong"; }
    }

    /**
     * "Here is the market I'm abandoning — work out what I'm owed."
     *
     * A first message like Query, deliberately outside the handshake: the client is
     * holding a history this host has already refused, so there is nothing to sync and
     * no session to establish. The host verifies the branch and writes one event; the
     * client then resets and joins normally.
     */
    public static class MigrateRequest extends Message {
        public String userId;
        public List<String> logLines;
        /** False on every chunk but the last — a whole history in one frame can
         *  exceed MessageChannel's per-line cap, so the transfer is split. */
        public boolean complete = true;
        public MigrateRequest() { type = "MigrateRequest"; }
    }

    public static class MigrateResult extends Message {
        public boolean accepted;
        public String reason;
        public long credits;
        public String summary;      // human-readable "1400 credits, 50 iron"
        public MigrateResult() { type = "MigrateResult"; }
    }

    /**
     * "You are behind me on your own market — here is the rest."
     *
     * Only legal when the host's head is an ancestor of the client's, i.e. a
     * fast-forward. The events were already sequenced, already signed, and chain
     * directly onto the host's head, so adopting them is indistinguishable from
     * having received them live. A stale host can rejoin its own market without
     * anyone hand-coordinating who hosts next.
     */
    public static class CatchUp extends Message {
        public String userId;
        public List<String> logLines;   // events after the host's current head
        /** False on every chunk but the last, exactly as MigrateRequest does it —
         *  the divergence being repaired here has no bound on its length either. */
        public boolean complete = true;
        public CatchUp() { type = "CatchUp"; }
    }

    public static class CatchUpResult extends Message {
        public boolean accepted;
        public String reason;
        public int applied;
        public CatchUpResult() { type = "CatchUpResult"; }
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
        public String marketId;       // which market this host is serving
        public String marketName;
        public QueryReply() { type = "QueryReply"; }
    }

}