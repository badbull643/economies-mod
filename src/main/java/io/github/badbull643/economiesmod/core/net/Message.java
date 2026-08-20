package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.PeerCache;
import io.github.badbull643.economiesmod.core.WorldAttestation;

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
        /**
         * What this client says about the world it trades from, or null.
         *
         * Unsigned, and deliberately not part of the handshake's trust. Signing it would
         * only prove who made the claim, which nobody doubts — the claim itself is
         * unverifiable either way, and dressing it in a signature would suggest
         * otherwise. See WorldAttestation for what it is actually good for.
         */
        public WorldAttestation attestation;
        public Hello() { type = "Hello"; }
    }

    public static class Propose extends Message {
        public String clientEventId;
        public String eventType;
        public String eventJson;
        public String signature;      // base64, over canonicalPayload(event)
        public Propose() { type = "Propose"; }
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
        /**
         * Whether this host is a dedicated server rather than somebody's game.
         *
         * The one player-facing difference between the two hosting modes, and the only
         * one worth surfacing: it answers "will this still be here tomorrow, and do I
         * need to take a turn hosting". Everything else about them is identical by
         * design, which is why there is no client-side mode toggle to go with it.
         */
        public boolean dedicated = false;
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
         *  the host can't tell, because Hello only carries the client's own head.
         *
         *  Sent with FORK for a different reason: the client can see that it diverged,
         *  but not where, and the re-place checklist a reset offers is computed against
         *  exactly that point. Without it the only thing that ever learned the split
         *  point was the discovery poll, so a fork found by connecting produced an
         *  empty checklist. */
        public long hostSeq;
        public String hostHash;
        /** Who is refusing, so a divergence recorded from a refusal carries the same
         *  label as one recorded from a poll — they have to match, or the poll cannot
         *  recognise its own earlier warning to clear it. */
        public String hostName;
        public Error() { type = "Error"; }
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

    /**
     * "What I said about my world has changed."
     *
     * Sent after the handshake, because the handshake is a photograph and the thing it
     * describes can change underneath it. Connecting from a clean world and then opening
     * it to LAN with cheats enabled would otherwise leave the host holding a description
     * that stopped being true a minute after it was given.
     *
     * Unsigned, like the attestation on Hello and for the same reason: signing would
     * prove who said it, which nobody doubts, and imply a guarantee that does not exist.
     */
    public static class Attest extends Message {
        public WorldAttestation attestation;
        public Attest() { type = "Attest"; }
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
        /**
         * Whether this host is a dedicated server. Carried here as well as on Sync
         * because the host list is built from discovery, which never gets a Sync — a
         * badge that only appeared after connecting would be answering the question
         * too late to be of use in choosing.
         *
         * Self-reported, and inside the signed payload. Signing does not make it true —
         * a host can describe itself however it likes — but it stops anyone else
         * changing the answer in transit, which matters more than usual given the
         * transport is assumed to be a trusted mesh rather than encrypted.
         */
        public boolean dedicated;
        public QueryReply() { type = "QueryReply"; }
    }

}