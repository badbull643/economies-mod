package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that differs between a rotating host and a dedicated server.
 *
 * The two are the same engine — HostServer has never known which it is, and from a
 * player's seat a dedicated server is just a host that is always up. What separates
 * them is policy: how much a newcomer is granted, how many may connect, which address
 * to bind. Keeping that in one object is what makes "one config difference" true
 * rather than aspirational, and it is why there is no client-side mode toggle: a
 * setting that made the two look different would be describing a difference that is
 * not there.
 *
 * A client builds one of these in code with friend-group defaults. A dedicated server
 * loads it from JSON. Nothing else changes between them.
 *
 * Read at startup rather than saved on mutate, unlike Settings — an operator edits this
 * file and restarts, and a server that rewrote its own config would fight them for it.
 */
public class ServerConfig {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // ─────────── network ───────────

    public int port = 25555;

    /**
     * Which interface to listen on; null or empty means all of them.
     *
     * Worth having even before there is much policy around it: a box with a public
     * address usually also has a private one, and "listen on everything" is a decision
     * rather than the only option.
     */
    public String bindAddress = null;

    /** Refused beyond this, so one host cannot be exhausted by connections alone. */
    public int maxConnections = 64;

    /**
     * Events a single client may fall behind before it is dropped.
     *
     * Fan-out is per-client and asynchronous, so a slow reader backs up in its own
     * queue instead of stalling the sequencer. That queue has to be bounded, or a
     * client that has stopped reading becomes an unbounded memory leak on the host.
     * Deep enough to absorb an ordinary stall, shallow enough that a client which is
     * genuinely gone is dropped rather than accumulated.
     */
    public int outboundQueueDepth = 256;

    // ─────────── admission ───────────

    /**
     * "open" to admit anyone, "allowlist" to admit only listed identities.
     *
     * Server-local, deliberately: refusing a connection is not a market fact. It writes
     * no event, needs no signature, and two hosts of the same market may reasonably
     * disagree about who they will talk to. Putting it in the ledger would make every
     * replica replay one operator's guest list as if it were history.
     *
     * This is also the honest limit of what admission buys. It cannot tell whether a
     * player's items were legitimately obtained — their world is theirs, and depositing
     * goods from a creative-mode world uses the completely honest client path. What it
     * buys is the ability to impose conditions as a price of entry, which is a different
     * and smaller claim than anti-cheat.
     */
    public String admission = OPEN;

    public static final String OPEN = "open";
    public static final String ALLOWLIST = "allowlist";

    /** Identities admitted when admission is "allowlist". Ignored when open. */
    public List<String> allow = new ArrayList<>();

    /** Identities refused regardless of admission mode. Checked first. */
    public List<String> deny = new ArrayList<>();

    /**
     * Why this identity is not welcome, or null when it is.
     *
     * Deny beats allow, which is the conventional order and the safe one: an identity
     * that appears on both lists is being argued about, and refusing is the reading you
     * can undo. Case-insensitive because a UUID is hex and reaches the config file by
     * being typed or pasted by a human.
     */
    public String refuses(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            return "no identity presented";
        }
        String id = userId.trim();

        if (listed(deny, id)) {
            return "that identity is not allowed on this server";
        }
        if (ALLOWLIST.equalsIgnoreCase(admission) && !listed(allow, id)) {
            return "this server admits invited identities only";
        }
        return null;
    }

    private static boolean listed(List<String> list, String id) {
        if (list == null) return false;
        for (String entry : list) {
            if (entry != null && entry.trim().equalsIgnoreCase(id)) return true;
        }
        return false;
    }

    // ─────────── identity ───────────

    public String hostName = "dedicated";

    /**
     * The identity the server signs grants and sequencing with.
     *
     * Null means "derive one and remember it". The old launcher fell back to a
     * hardcoded UUID, which meant every dedicated server that did not pass one was the
     * same participant as every other — harmless while there was one, wrong as soon as
     * two of them ever met the same client.
     */
    public String hostUserId = null;

    // ─────────── market ───────────

    public String logFile = "server-market.jsonl";

    public String marketName = null;

    /**
     * Who is recorded as having created the market, when this server bootstraps one.
     *
     * Set alongside --creator-key so the market's genesis is signed by a key the
     * operator holds rather than by the server's own. WelcomeGrant is not creator-gated
     * — validation checks only the target, never the author — so the server can still
     * sign grants with its own key afterwards. That split is the point: compromising
     * the box gets grant-signing and denial of service, not authority over the market.
     */
    public String creatorUserId = null;

    /**
     * Credits handed to each identity the first time it registers.
     *
     * The only source of money in the system and, for now, an unbounded one — there is
     * no sink until the transaction tax exists. Configurable because a friend group and
     * a public server want very different numbers, and because the number is otherwise
     * a constant recompiled per deployment.
     */
    public long welcomeGrant = DEFAULT_WELCOME_GRANT;

    /** Lives here rather than on HostServer: it is policy, and this is where policy is. */
    public static final long DEFAULT_WELCOME_GRANT = 1000L;

    // ─────────── loading ───────────

    /**
     * Reads a config. Absent is fine; present and unreadable is not.
     *
     * The tempting rule is "always fall back to defaults, a server that will not start
     * is down at exactly the moment somebody is trying to fix it". That is right about
     * availability and wrong about policy. welcomeGrant is not a preference — it is the
     * only source of money in the system, and it writes events that can never be
     * rewritten. An operator who set 50 and whose file later got truncated would get a
     * server quietly minting 1000 a head into a permanent log. bindAddress fails the
     * same way, turning a localhost-only server into a public one.
     *
     * So the two cases are separated. No file means no policy has been expressed yet
     * and defaults are the only available answer. A file that exists but cannot be read
     * means policy was expressed and we cannot see it, and guessing is worse than
     * stopping — the operator is one corrected file and one restart away either way.
     */
    public static ServerConfig load(Path file) throws IOException {
        if (!Files.exists(file)) return new ServerConfig();

        String json;
        try {
            json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("cannot read " + file + " (" + e.getMessage()
                    + ") — fix it, or move it aside to start with defaults");
        }

        ServerConfig loaded;
        try {
            loaded = gson.fromJson(json, ServerConfig.class);
        } catch (Exception e) {
            throw new IOException(file + " is not valid JSON (" + e.getMessage()
                    + ") — fix it, or move it aside to start with defaults");
        }

        // Gson hands back null for an empty or whitespace-only file rather than
        // complaining, which would otherwise read as "no policy" for a file that
        // plainly is one.
        if (loaded == null) {
            throw new IOException(file + " is empty — delete it to start with defaults");
        }
        return loaded;
    }

    /** Writes this config out, so an operator has a file to edit rather than a guess. */
    public void save(Path file) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, gson.toJson(this).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Rejects values that would produce a server nobody can use.
     *
     * Returns the complaint, or null when it is fine. Checked rather than clamped: a
     * port silently changed out from under an operator is worse than a refusal that
     * names the field.
     */
    public String problem() {
        if (port < 1 || port > 65535) {
            return "port must be between 1 and 65535, not " + port;
        }
        if (maxConnections < 1) {
            return "maxConnections must be at least 1, not " + maxConnections;
        }
        if (welcomeGrant < 0) {
            return "welcomeGrant cannot be negative, not " + welcomeGrant;
        }
        if (outboundQueueDepth < 1) {
            return "outboundQueueDepth must be at least 1, not " + outboundQueueDepth;
        }
        // A misspelt mode must not quietly read as "open". An operator who typed
        // "allowlist " or "allow-list" and got an open server would have no way to tell
        // from the outside until the wrong person connected.
        if (!OPEN.equalsIgnoreCase(admission) && !ALLOWLIST.equalsIgnoreCase(admission)) {
            return "admission must be \"" + OPEN + "\" or \"" + ALLOWLIST
                    + "\", not \"" + admission + "\"";
        }
        if (ALLOWLIST.equalsIgnoreCase(admission) && (allow == null || allow.isEmpty())) {
            return "admission is \"" + ALLOWLIST + "\" but allow is empty —"
                    + " that server would refuse everyone, including you";
        }
        return null;
    }

    /** The defaults a client hosting for friends uses. Explicit, so it reads as a choice. */
    public static ServerConfig friendGroup(int port) {
        ServerConfig cfg = new ServerConfig();
        cfg.port = port;
        return cfg;
    }
}
