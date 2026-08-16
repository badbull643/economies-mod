package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
     * Reads a config, falling back to defaults for anything absent.
     *
     * A malformed file is reported and then ignored rather than fatal: a server that
     * refuses to start because one field was mistyped is a server that is down at
     * exactly the moment somebody is trying to fix it.
     */
    public static ServerConfig load(Path file) {
        ServerConfig cfg = new ServerConfig();
        try {
            if (Files.exists(file)) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                ServerConfig loaded = gson.fromJson(json, ServerConfig.class);
                if (loaded != null) cfg = loaded;
            }
        } catch (Exception e) {
            System.err.println("[host] could not read " + file + " (" + e
                    + ") — continuing with defaults");
        }
        return cfg;
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
        return null;
    }

    /** The defaults a client hosting for friends uses. Explicit, so it reads as a choice. */
    public static ServerConfig friendGroup(int port) {
        ServerConfig cfg = new ServerConfig();
        cfg.port = port;
        return cfg;
    }
}
