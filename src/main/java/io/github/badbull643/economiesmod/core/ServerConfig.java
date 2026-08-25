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

    // ─────────── deposit limits ───────────

    /**
     * Items one identity may deposit per window. Zero disables the cap.
     *
     * Off by default. A friend group's market has no cheating problem worth a ceiling,
     * and a limit that surprises people mid-session is worse than no limit at all. This
     * is for hosts admitting people they do not know.
     *
     * Host policy, not market policy, and it cannot be otherwise: the answer depends on
     * when the question is asked, so a replica replaying the log later would evaluate a
     * window long since passed and refuse events the market legitimately contains. See
     * DepositLimiter.
     */
    public long maxDepositUnitsPerWindow = 0;

    /** How far back the deposit cap counts. */
    public int depositWindowMinutes = DEFAULT_DEPOSIT_WINDOW_MINUTES;

    /**
     * The compiled window, named because adopt() has to recognise it.
     *
     * "The operator has not chosen a window" is expressed here as "it still equals the
     * compiled one", so that value has to be a thing with a name rather than a literal
     * repeated in two places.
     */
    public static final int DEFAULT_DEPOSIT_WINDOW_MINUTES = 60;

    // ─────────── world attestation ───────────
    //
    // Everything below acts on what a client says about its own world, which it can
    // lie about freely. See WorldAttestation: the value is in catching the casual case
    // and in making two claims contradict each other, never in believing either one.
    // All off by default, because a policy this soft should be a deliberate choice.

    /** Turn away clients that decline to describe their world, or are too old to. */
    public boolean requireAttestation = false;

    /** Turn away worlds reporting creative mode. */
    public boolean refuseCreativeWorlds = false;

    /**
     * Turn away worlds reporting commands enabled.
     *
     * Blunter than it looks: plenty of honest players enable cheats to set the time or
     * fix a mistake, so this refuses a large number of people who have not fabricated
     * anything. Off by default for that reason, and worth pairing with a message the
     * operator can explain.
     */
    public boolean refuseCheatWorlds = false;

    /**
     * Items per claimed hour of play that this server finds plausible. Zero disables.
     *
     * The contradiction check. It cannot tell whether a world is really as old as it
     * says — but it makes a young world a small allowance and an old one a specific,
     * recorded claim. See WorldAttestation.
     *
     * <h2>Know what this costs before switching it on</h2>
     *
     * Weighed live and left off deliberately. Two things make it the weakest of the
     * three deposit rules:
     *
     * It compares a rolling window against a lifetime. The total it judges is what the
     * identity has deposited inside depositWindowMinutes; the allowance is the whole
     * world's play time times this rate. So it reads as "items this hour ≤ lifetime
     * hours × rate" — barely binding on an established world, harsh on a new one, and
     * keyed to a number that has nothing to do with cheating. A restart clears the
     * window too, since the limiter keeps it in memory.
     *
     * And it is a rate limit applied to a stock. Play time accrues linearly; a farm's
     * output arrives in bursts. A tree farm fills a chest in minutes, and depositing it
     * is refused on a young world however honestly it was grown.
     *
     * Both costs land on honest players, because the hours are self-reported: a
     * modified client claims whatever it likes and passes. maxDepositUnitsPerWindow
     * needs no client cooperation at all, and maxDepositMultipleOfHandled is keyed to
     * statistics the game maintains and /give does not touch. Prefer those two.
     */
    public long maxDepositUnitsPerPlayHour = 0;

    /**
     * How many times a player's own statistics they may deposit. Zero disables.
     *
     * Minecraft counts what you mine, craft and pick up, and /give increments none of
     * it. So somebody handing over four thousand diamonds having picked up twelve is
     * contradicting a record they did not write and cannot restate — the strongest
     * signal available here, and the only one that outlives the mod being switched off.
     *
     * A multiple rather than a limit, because the count is a floor and not a measure:
     * smelted output and anything taken from a chest never touch PICKED_UP, so an
     * honest player's real total is always higher than their statistics say. Three is
     * generous enough for that and still catches the case worth catching, where the gap
     * is not a factor of three but of hundreds.
     */
    public int maxDepositMultipleOfHandled = 0;

    /**
     * The most credits one migration may carry in. Zero disables.
     *
     * The deposit rules above weigh the *items* somebody brings against evidence they
     * did not write. Nothing weighed their credits, and a migration carries a player's
     * whole balance from the market they came from — so two people arriving from a
     * market that grants 1000 into one that grants 50 multiply its money supply by
     * twenty-one, and the people who were already there go from holding all of the money
     * to holding five per cent of it. Nobody is robbed; everybody is outbid.
     *
     * Not a fraud check, and it does not need to be. A group with different grants
     * merging honestly produces exactly the same arithmetic as somebody doing it on
     * purpose, and the receiving market is the only party that can say what it is
     * willing to absorb. This lets it say so.
     *
     * Host-local like every other limit here, so it changes when hosting rotates — see
     * the log's note on host rules not travelling. Zero by default, so no existing
     * server starts refusing migrations it used to accept.
     *
     * Set it against what a newcomer here would be given: a cap a little above this
     * market's own welcome grant says "arrive with what a local would have", which is
     * usually the intent.
     */
    public long maxMigratedCredits = 0;

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

    /**
     * Takes on the host rules a market's creator published, where this host has not
     * spoken for itself.
     *
     * The whole of backlog item 8's payoff, and the precedence is the entire design:
     * <b>a local setting always wins.</b> Published rules fill in what the operator left
     * alone, so a friend rotating in picks up the group's caps instead of having none —
     * and anybody who has opened their own file keeps exactly what they wrote there.
     *
     * "Has not spoken" is decided per field, and the fields differ in how they say it.
     * {@code acceptsMigration} and {@code maxWelcomeGrant} are boxed, so null is silence.
     * The rest carry a compiled default that doubles as off, so silence is a value equal
     * to that default — which is imperfect and deliberately so: an operator who sets a
     * cap to exactly the compiled default gets the group's figure instead. That is a
     * strictly better outcome than the alternative reading, where a group's caps are
     * ignored by every host that never touched the file, which is the failure this
     * exists to fix.
     *
     * Nothing here can fail or refuse. A market whose published rules this build does
     * not understand hosts on its own settings, exactly as it would have before.
     */
    public void adopt(Event.HostDefaults published) {
        if (published == null) return;

        if (acceptsMigration == null) acceptsMigration = published.acceptsMigration;
        if (maxWelcomeGrant == null) maxWelcomeGrant = published.maxWelcomeGrant;

        if (maxDepositUnitsPerWindow == 0 && published.maxDepositUnitsPerWindow != null) {
            maxDepositUnitsPerWindow = published.maxDepositUnitsPerWindow;
        }
        if (depositWindowMinutes == DEFAULT_DEPOSIT_WINDOW_MINUTES
                && published.depositWindowMinutes != null) {
            depositWindowMinutes = published.depositWindowMinutes;
        }
        if (maxMigratedCredits == 0 && published.maxMigratedCredits != null) {
            maxMigratedCredits = published.maxMigratedCredits;
        }
        // Admission is a pair — a mode and the list it reads — so they move together or
        // a host adopts "allowlist" with nobody on it and locks its own market out.
        if (OPEN.equalsIgnoreCase(admission) && (allow == null || allow.isEmpty())
                && published.admission != null && isAdmissionMode(published.admission)) {
            admission = published.admission;
            if (published.allow != null) allow = new ArrayList<>(published.allow);
        }
        if ((deny == null || deny.isEmpty()) && published.deny != null) {
            deny = new ArrayList<>(published.deny);
        }
    }

    /**
     * Whether this is a mode a host understands.
     *
     * One place, because the list is now asked in two: a config file being checked, and
     * a set of host rules the creator is publishing into the log. Two copies would drift
     * and a market could carry a mode no host accepts.
     */
    public static boolean isAdmissionMode(String mode) {
        return OPEN.equalsIgnoreCase(mode) || ALLOWLIST.equalsIgnoreCase(mode);
    }

    /** Identities admitted when admission is "allowlist". Ignored when open. */
    public List<String> allow = new ArrayList<>();

    /** Identities refused regardless of admission mode. Checked first. */
    public List<String> deny = new ArrayList<>();

    /**
     * Add an identity to deny when it is caught changing its world after being admitted.
     *
     * Only that case. Arriving with cheats already on is refused at the door and needs
     * no permanent record — exclusion has already happened, and banning somebody for a
     * world they told you about honestly punishes the honesty. Being admitted under one
     * description and then changing the thing that was checked is a different act, and
     * the only one here that looks like a decision rather than a state.
     *
     * Off by default, and worth thinking about before switching on: the evidence is a
     * self-report, so this bans people who told the truth about themselves and never
     * touches anyone running a modified client. It is a rule for a server whose players
     * are honest and whose operator wants a hard line, not a defence against cheating.
     */
    public boolean banOnWorldChange = false;

    /**
     * Where this config was read from, so a ban can be written back.
     *
     * Transient: it is a fact about this run, not a setting, and Gson would otherwise
     * write a machine-specific path into a file an operator may well copy elsewhere.
     */
    public transient Path sourceFile;

    /**
     * Adds an identity to the deny list and writes it down. True if it was not already
     * there.
     *
     * Synchronized with {@link #refuses}, which walks the same list from every
     * connection thread — a ban arriving mid-iteration would otherwise throw rather
     * than refuse anybody.
     *
     * Persisted immediately, because a ban that only lasts until the next restart is
     * not the thing the word describes. Left in the file for an operator to remove,
     * which is the only way back.
     */
    public synchronized boolean ban(String userId) {
        if (userId == null || userId.trim().isEmpty()) return false;
        if (deny == null) deny = new ArrayList<>();
        if (listed(deny, userId.trim())) return false;

        deny.add(userId.trim());
        if (sourceFile != null) {
            try {
                save(sourceFile);
            } catch (IOException e) {
                // The ban still holds for this run; it just will not survive a restart.
                System.err.println("[host] banned " + userId + " but could not write it"
                        + " to " + sourceFile + ": " + e.getMessage());
            }
        }
        return true;
    }

    /**
     * Why this identity is not welcome, or null when it is.
     *
     * Deny beats allow, which is the conventional order and the safe one: an identity
     * that appears on both lists is being argued about, and refusing is the reading you
     * can undo. Case-insensitive because a UUID is hex and reaches the config file by
     * being typed or pasted by a human.
     */
    public synchronized String refuses(String userId) {
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
     * Whether to describe this host as a dedicated server to clients.
     *
     * Set by the standalone launcher, not by anything a player can reach. It is the
     * player-facing signal that this host is always up and nobody needs to take a turn
     * hosting — and, through acceptsMigration below, the default answer to whether
     * somebody may carry a balance in from elsewhere.
     */
    public boolean dedicated = false;

    /**
     * Whether this host accepts migrations. Unset means "whatever suits this kind of
     * host": **off for a dedicated server, on for somebody's game.**
     *
     * Migration exists to solve bootstrapping among people who know each other — a log
     * handed over on Discord, a group merging two markets they both meant to be one.
     * Everything it does rests on the receiving market being willing to take a stranger's
     * arithmetic on trust, bounded only by what their own Minecraft statistics can be
     * made to support.
     *
     * A dedicated server is the deployment where that assumption is worst. It is the one
     * that admission policy, deposit caps and attestation all exist for, and it is the
     * one where an arriving player is most likely to be somebody nobody vouches for. The
     * balance they carry in was set by a welcome grant *they chose*, in a world they
     * control, up to MAX_WELCOME_GRANT — so migration there is not "bringing your
     * savings", it is "naming your opening balance".
     *
     * Off by default there, then, and the answer for somebody who wants to join is the
     * one that costs them nothing: add another market in their world and connect from
     * that slot. Slots are separate logs, so their own economy is untouched and they
     * arrive here on the same welcome grant as everybody else.
     *
     * Boxed rather than a plain boolean so unset is distinguishable from explicitly
     * false — an operator who wants migrations on a dedicated box can say so, and one
     * who never thinks about it gets the safe answer for the kind of host they are
     * running.
     */
    public Boolean acceptsMigration = null;

    /** The effective answer, with the default resolved. Ask this, never the field. */
    public boolean acceptsMigration() {
        return acceptsMigration != null ? acceptsMigration : !dedicated;
    }

    /**
     * The largest welcome grant somebody hosting in their game will sequence.
     *
     * Ten thousand against items that trade for one or two. The compiled ceiling is a
     * hundred times that, which is four orders of magnitude above anything a real market
     * uses — and the grant is the largest single lever on what credits are worth, so a
     * fat finger there does more damage than every other setting combined.
     */
    public static final long ROTATING_MAX_WELCOME_GRANT = 10_000L;

    /**
     * The most this host will let a market grant a newcomer. Unset follows the kind of
     * host: **the low figure above in somebody's game, the compiled ceiling on a
     * dedicated server.**
     *
     * A host rule, and it could not be anything else. The ceiling in EventApplier is
     * replicated, so every replica has to reach the same verdict about the same event —
     * and "rotating" versus "dedicated" describes whoever is hosting at this moment, not
     * the market. A ceiling that differed between them would make one policy event legal
     * on one host and illegal on the next, and hosting rotates by design.
     *
     * Which is also why nothing existing breaks. Lowering MarketState.MAX_WELCOME_GRANT
     * would have left any market that had already set a larger grant unable to replay its
     * own recorded policy — the objection that kept this on the backlog. Refusing to
     * *sequence* a new one has no such reach: history stays valid, and only the next
     * change is judged.
     *
     * A dedicated server keeps the high figure because it is the deployment that might
     * legitimately want it — a long-running economy with real scarcity, set once by an
     * operator who thought about it — and because it is the one where the number is not
     * one keystroke from a player who wondered what would happen.
     */
    public Long maxWelcomeGrant = null;

    /** The effective answer, with the default resolved. Ask this, never the field. */
    public long maxWelcomeGrant() {
        if (maxWelcomeGrant != null) return maxWelcomeGrant;
        return dedicated ? MarketState.MAX_WELCOME_GRANT : ROTATING_MAX_WELCOME_GRANT;
    }

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

    /**
     * Credits charged to place an order, written into this market's opening policy.
     *
     * Here for the same reason welcomeGrant is: a dedicated server is the creator of any
     * market it bootstraps, and a server has no screen to set policy from afterwards.
     * Without this, a market created by a server the ordinary way could never charge a
     * listing fee at all — its creator is the box, and only a creator may change policy.
     *
     * Superseded for everything after genesis by {@link #policy}, which lets a server
     * that created its market change these whenever it likes. This field still writes the
     * OPENING fee, and --creator-key still names a player as creator when a person should
     * own the market from their own screen — but neither is any longer the difference
     * between a tunable market and a frozen one.
     */
    public long listingFee = 0;

    /**
     * Credits each identity may claim once per interval, written into the opening policy.
     *
     * Needs listingFee above it to be non-zero, and small enough that the fees collected
     * over an interval exceed one payment — otherwise anybody could earn it by trading
     * with themselves. problem() refuses the pairing rather than letting a server write
     * a genesis policy every replica would reject, which would leave the market unusable
     * from its first event.
     */
    public long stipendAmount = 0;


    /** Lives here rather than on HostServer: it is policy, and this is where policy is. */
    public static final long DEFAULT_WELCOME_GRANT = 1000L;

    /**
     * The market's own economics, for a server that created the market it serves.
     *
     * <h2>Why this is a block and not six more fields at the top</h2>
     *
     * Everything else in this file is a <b>host rule</b>: what this box does with the
     * people connecting to it. Change one, restart, done — it is nobody's business but
     * the operator's. What is in here is different in kind. These are recorded in the
     * market's log, every replica replays them, and they are the same for everyone
     * hosting it. The file gave no sign of that distinction, so three of them sat
     * among the host rules looking like ordinary settings while quietly doing nothing
     * after the first start — which is exactly what somebody reading the file assumed
     * was not happening.
     *
     * <h2>How it is applied</h2>
     *
     * On start, a server that is the market's creator publishes a MarketPolicy event
     * for whatever differs here, exactly as a player would from the Market screen. That
     * is not a host overruling policy: the creator is who this server is, and the event
     * is validated by every replica like any other. A server hosting a market it did
     * not create says so and changes nothing, because it has no authority to.
     *
     * <h2>null means leave alone</h2>
     *
     * Every field is boxed on purpose, following {@link #maxWelcomeGrant} and
     * {@link #acceptsMigration}. An absent value is not zero — it is "do not touch",
     * which is the only safe reading when a freshly written config meets a market that
     * has been running for a month. A primitive here would let an unmentioned listing
     * fee reset a real market's fee to nothing on the next restart.
     */
    public Policy policy = new Policy();

    /** @see #policy */
    public static class Policy {
        /** Transaction tax in basis points. 100 = 1%. */
        public Integer taxBps;
        public Long welcomeGrant;
        public Long listingFee;
        /** Orders each identity may hold open before the listing fee escalates. */
        public Integer listingFreeOrders;
        public Long stipendAmount;
        /** Fills between stipend claims. */
        public Long stipendEveryFills;

        public boolean isEmpty() {
            return taxBps == null && welcomeGrant == null && listingFee == null
                    && listingFreeOrders == null && stipendAmount == null
                    && stipendEveryFills == null;
        }

        /**
         * Fills in whatever is unset from the numbers actually in force.
         *
         * For {@code --write-config}, and for that alone. Left to itself this block
         * serialises as {@code "policy": {}} — every field null, and Gson omits nulls —
         * which is the correct state and a useless document: an operator cannot discover
         * from an empty object that six settings live in it, and the whole promise of
         * --write-config is a file with every setting in it. The first person to run it
         * after this block was added asked what was wrong with it, which is the answer.
         *
         * Written out with the market's own figures rather than the config's, so the file
         * describes the market that exists. That also makes the next start a no-op: every
         * value matches, nothing differs, no event is written. Editing one and restarting
         * is then the whole interface.
         */
        public void fillFrom(MarketState market) {
            if (market == null) return;
            if (taxBps == null) taxBps = market.taxBps();
            if (welcomeGrant == null) welcomeGrant = market.welcomeGrant();
            if (listingFee == null) listingFee = market.listingFee();
            if (listingFreeOrders == null) listingFreeOrders = market.listingFreeOrders();
            if (stipendAmount == null) stipendAmount = market.stipendAmount();
            if (stipendEveryFills == null) stipendEveryFills = market.stipendEveryFills();
        }

        /**
         * The same for a config written before any market exists.
         *
         * The opening policy a bootstrap would lay down, so the file shows what the first
         * start will create rather than nothing at all.
         */
        public void fillFromBootstrapDefaults(long grant, long fee, long stipend) {
            if (taxBps == null) taxBps = 0;
            if (welcomeGrant == null) welcomeGrant = grant;
            if (listingFee == null) listingFee = fee;
            if (listingFreeOrders == null) listingFreeOrders = 0;
            if (stipendAmount == null) stipendAmount = stipend;
            if (stipendEveryFills == null) {
                stipendEveryFills = MarketState.DEFAULT_STIPEND_EVERY_FILLS;
            }
        }
    }

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
        if (!Files.exists(file)) {
            // Still records where it would live, so a ban on a first run has somewhere
            // to be written rather than lasting only until the next restart.
            ServerConfig fresh = new ServerConfig();
            fresh.sourceFile = file;
            return fresh;
        }

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
        loaded.sourceFile = file;
        return loaded;
    }

    /** Writes this config out, so an operator has a file to edit rather than a guess. */
    public void save(Path file) throws IOException {
        write(file, effectiveTree());
    }

    /**
     * Every setting, with the boxed defaults resolved to what they currently mean.
     *
     * Gson omits nulls, so a Boolean left unset is a setting the operator cannot
     * discover exists — which is precisely how acceptsMigration shipped invisible:
     * present in the code, documented in the checklist, and absent from every config
     * file anyone had. Write the resolved answer instead of the unset marker.
     *
     * --help calls this "the effective config", and a default nobody can see is not
     * effective config. Written as the value it currently resolves to, so an operator
     * who wants the other one edits a line already in front of them rather than having
     * to know the key's name.
     *
     * On a tree rather than by mutating this, so saving a config does not change it.
     *
     * One method rather than one per caller: hostRulesTree below subtracts from this,
     * and a second place resolving the same defaults would be free to resolve them
     * differently.
     */
    private com.google.gson.JsonObject effectiveTree() {
        com.google.gson.JsonObject out = gson.toJsonTree(this).getAsJsonObject();
        out.addProperty("acceptsMigration", acceptsMigration());
        out.addProperty("maxWelcomeGrant", maxWelcomeGrant());
        return out;
    }

    private static void write(Path file, com.google.gson.JsonObject tree) throws IOException {
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, gson.toJson(tree).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Stamps this config with the facts about the session hosting it from a world.
     *
     * Port, name and identity are facts rather than settings — they belong to whoever
     * pressed Host, not to a file that may have been copied from somewhere else — and
     * dedicated is the sharpest of them, because it is what acceptsMigration and
     * maxWelcomeGrant resolve against. A config lifted from a dedicated server's
     * server-config.json would otherwise claim a 1,000,000 grant ceiling and refuse
     * migrations, in a world that is neither.
     *
     * One method because two callers must reach the same verdict: hosting builds the
     * config this way, and /trade hostconfig reports what hosting will do. Whichever of
     * them stamped one field differently would be reporting a market that does not
     * exist — and the fields are exactly the ones hostRulesTree drops, so no file can
     * put them back.
     */
    public ServerConfig asWorldHost(int port, String hostName, String hostUserId) {
        this.port = port;
        this.hostName = hostName;
        this.hostUserId = hostUserId;
        this.dedicated = false;
        return this;
    }

    /**
     * Every setting a market hosted from somebody's game can act on, resolved.
     *
     * Two groups are left out. MarketStateHolder.hostPolicyFor imposes port, hostName,
     * hostUserId and dedicated over whatever the file said, because they are facts about
     * this session rather than settings — and a key written into a file an operator is
     * about to edit, only to be silently overruled, is worse than one that is absent.
     * The rest — logFile, marketName, creatorUserId — are read only by the standalone
     * launcher's main, which bootstraps a market from them; a world's market is created
     * through the Market screen and never consults them.
     *
     * Left in deliberately: bindAddress, which HostServer honours whoever started it,
     * and welcomeGrant, which for a world decides whether grants are issued at all
     * rather than the amount — grantMismatchWarning already says that out loud when the
     * two disagree, and problem() weighs it against maxWelcomeGrant either way.
     *
     * Public because /trade hostconfig prints this and saveHostRules writes it, and
     * those two must not be able to disagree about what a host rule is. A command that
     * listed a setting the file it writes does not contain — or wrote one it never
     * mentioned — would send somebody looking for a key that is not there.
     */
    public com.google.gson.JsonObject hostRulesTree() {
        com.google.gson.JsonObject out = effectiveTree();
        for (String imposed : new String[] {
                "port", "hostName", "hostUserId", "dedicated",
                "logFile", "marketName", "creatorUserId" }) {
            out.remove(imposed);
        }
        return out;
    }

    /** hostRulesTree, written where a world hosting from this game will read it. */
    public void saveHostRules(Path file) throws IOException {
        write(file, hostRulesTree());
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
        if (listingFee < 0) {
            return "listingFee cannot be negative, not " + listingFee;
        }
        if (listingFee > MarketState.MAX_LISTING_FEE) {
            return "listingFee may not exceed " + MarketState.MAX_LISTING_FEE;
        }
        if (stipendAmount < 0) {
            return "stipendAmount cannot be negative, not " + stipendAmount;
        }
        if (stipendAmount > MarketState.MAX_STIPEND) {
            return "stipendAmount may not exceed " + MarketState.MAX_STIPEND;
        }
        // Checked here so the server refuses to start rather than writing a genesis
        // policy every replica would reject — a market unusable from its first event is
        // worse than one that never got created.
        if (stipendAmount > 0) {
            // The same rule EventApplier enforces, asked here so the server refuses to
            // start rather than writing a genesis policy every replica would reject.
            // Not a second copy of the arithmetic: this had one, with the doubled
            // cost-per-fill the applier used to have, and the two would have drifted the
            // moment either was corrected — which is exactly what happened.
            //
            // Judged for a single identity, because a market being created has none yet.
            // The applier asks again at every claim, where the real head count is known.
            String unsafe = EventApplier.stipendOutpacesItsFees(stipendAmount,
                    MarketState.DEFAULT_STIPEND_EVERY_FILLS, listingFee, 1);
            if (unsafe != null) return unsafe;
        }
        if (welcomeGrant < 0) {
            return "welcomeGrant cannot be negative, not " + welcomeGrant;
        }
        if (maxWelcomeGrant != null && maxWelcomeGrant < 0) {
            return "maxWelcomeGrant cannot be negative, not " + maxWelcomeGrant;
        }
        if (maxWelcomeGrant() > MarketState.MAX_WELCOME_GRANT) {
            return "maxWelcomeGrant may not exceed " + MarketState.MAX_WELCOME_GRANT
                    + ", which every replica enforces regardless of this setting";
        }
        // Caught here rather than at the first policy event, because a market this host
        // bootstraps writes its grant into genesis — and a host that refuses to sequence
        // the figure it just created a market with is a server that argues with itself.
        //
        // Only a host that bootstraps can argue with itself, which is the launcher and
        // nothing else: a market hosted from a world is created through the Market
        // screen by MarketBootstrap, which never reads this file. There welcomeGrant is
        // the on/off switch and nothing more — issueWelcomeGrant tests it against zero
        // and takes the amount from the market — so the two settings are about different
        // things and pairing them refuses a file that says nothing contradictory.
        //
        // It is not a harmless refusal either. hostPolicyFor answers an unusable file by
        // discarding all of it and hosting on the defaults, so lowering the ceiling in a
        // generated file — where welcomeGrant sits at its default of 1000 — silently
        // turned off every other rule in it, including the ceiling being lowered. Found
        // running E17, which is the second time this pair has been wrong in the same
        // direction: a rule about who is hosting, asked where it does not apply.
        if (dedicated && welcomeGrant > maxWelcomeGrant()) {
            return "welcomeGrant is " + welcomeGrant + " but this host will not sequence"
                    + " a grant above " + maxWelcomeGrant()
                    + " — raise maxWelcomeGrant if that is really what you want";
        }
        if (maxMigratedCredits < 0) {
            return "maxMigratedCredits cannot be negative, not " + maxMigratedCredits
                    + " — use 0 to accept any balance";
        }
        if (outboundQueueDepth < 1) {
            return "outboundQueueDepth must be at least 1, not " + outboundQueueDepth;
        }
        if (maxDepositUnitsPerWindow < 0) {
            return "maxDepositUnitsPerWindow cannot be negative, not "
                    + maxDepositUnitsPerWindow;
        }
        // A window of zero would refuse every deposit forever under a cap, since
        // nothing would ever age out of it — and under the other two rules it silently
        // stops deposits being counted at all, which is worse than refusing: the rule
        // still answers, it just answers about one deposit instead of the window.
        if (countsDeposits() && depositWindowMinutes < 1) {
            return "depositWindowMinutes must be at least 1 when any deposit rule is set";
        }
        // A misspelt mode must not quietly read as "open". An operator who typed
        // "allowlist " or "allow-list" and got an open server would have no way to tell
        // from the outside until the wrong person connected.
        if (!isAdmissionMode(admission)) {
            return "admission must be \"" + OPEN + "\" or \"" + ALLOWLIST
                    + "\", not \"" + admission + "\"";
        }
        if (ALLOWLIST.equalsIgnoreCase(admission) && (allow == null || allow.isEmpty())) {
            return "admission is \"" + ALLOWLIST + "\" but allow is empty —"
                    + " that server would refuse everyone, including you";
        }
        return null;
    }

    /**
     * Whether any configured rule needs deposits counted over a window.
     *
     * One place, asked by both the host that builds the limiter and the validation that
     * insists on a usable window, because the two disagreeing is the bug this closes.
     * maxDepositMultipleOfHandled was missing from the host's version, so setting the
     * statistics rule alone gave a limiter with a zero-length window: it tracked
     * nothing, every deposit was judged on its own against the multiple, and somebody
     * with ten handled could deposit thirty as often as they liked. See DepositLimiter,
     * whose tracking() exists for exactly this and was written up for the play-hour
     * rule before this one existed.
     */
    public boolean countsDeposits() {
        return maxDepositUnitsPerWindow > 0
                || maxDepositUnitsPerPlayHour > 0
                || maxDepositMultipleOfHandled > 0;
    }

    /** The defaults a client hosting for friends uses. Explicit, so it reads as a choice. */
    public static ServerConfig friendGroup(int port) {
        ServerConfig cfg = new ServerConfig();
        cfg.port = port;
        return cfg;
    }
}
