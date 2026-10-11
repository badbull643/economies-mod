package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Runs a dedicated server's bots: spawns them, schedules their decisions, produces their
 * goods, pauses and resumes them.
 *
 * Nothing here is trusted by anyone else. Every event it makes goes through the sequencer
 * like a player's proposal would and is checked by every replica against the fixed rules in
 * BotRules — the runner decides when a bot acts and what it asks for, the rules decide what
 * is allowed. So the schedulers, the budget and the brains can all change between versions
 * of the mod without changing what a log means.
 *
 * Two schedulers, picked per bot when it is spawned:
 *  - tick: one thread, a 50 ms tick like a Minecraft server's, each bot deciding every so
 *    many ticks. Every bot in the group sees the market in turn, never two at once.
 *  - threads: a fixed pool, each bot on its own repeating task, so several decide at the same
 *    moment and race to the sequencer. Mostly for testing that the engine holds up to it.
 *
 * Bot keys live in a folder beside the log, one file per bot, and bots.json there records
 * which scheduler each bot runs on. The market itself records everything else.
 */
public final class BotRunner {

    public enum Mode {
        TICK, THREADS;

        public String word() { return name().toLowerCase(Locale.ROOT); }

        public static Mode parse(String s) {
            if (s == null) return null;
            switch (s.toLowerCase(Locale.ROOT)) {
                case "tick": case "ticks": return TICK;
                case "thread": case "threads": case "pool": return THREADS;
                default: return null;
            }
        }
    }

    /** The runner's clocks and rates. Fixed in a real server; a test shortens them. */
    static final class Timing {
        long tickMs = 50;
        /** How often each type decides, in ticks (and, on the pool, in the same time). */
        int producerEvery = 600;      // 30 s
        int makerEvery = 200;         // 10 s
        /** How often production is looked at. A producer only produces once a whole lot is owed. */
        int productionEvery = 1_200;  // 60 s
        int poolSize = 4;
        /** The action budget: a bucket of this many actions, one more every refillMs. */
        int budgetCapacity = 8;
        long budgetRefillMs = 15_000;
        LongSupplier clock = System::currentTimeMillis;

        int every(String type) {
            return BotRules.PRODUCER.equals(type) ? producerEvery : makerEvery;
        }
    }

    private final HostServer server;
    private final Path dir;
    private final Path registryFile;
    private final Timing timing;
    private final Gson gson = new Gson();

    private final Map<UUID, Running> running = new ConcurrentHashMap<>();
    /** Which scheduler each bot runs on, as saved in bots.json. */
    private final Map<String, String> modes = new ConcurrentHashMap<>();

    private final AtomicLong tick = new AtomicLong();
    private final AtomicBoolean productionQueued = new AtomicBoolean();
    /** Spreads tick-mode bots across the ticks so a group does not all decide on the same one. */
    private final AtomicInteger nextOffset = new AtomicInteger();

    private ScheduledExecutorService ticker;
    private ScheduledExecutorService pool;

    public BotRunner(HostServer server, Path dir) {
        this(server, dir, new Timing());
    }

    BotRunner(HostServer server, Path dir, Timing timing) {
        this.server = server;
        this.dir = dir;
        this.registryFile = dir.resolve("bots.json");
        this.timing = timing;
    }

    /** One bot this runner is running: its key, its algorithm, and what it keeps between ticks. */
    private final class Running {
        final UUID id;
        final String type;
        final PlayerKeys keys;
        final BotBrain brain;
        final Mode mode;
        final int offset;
        final Budget budget = new Budget(timing.budgetCapacity, timing.budgetRefillMs,
                timing.clock.getAsLong());
        final Map<String, Object> memory = new HashMap<>();
        /** A batch is queued and not yet applied: the next decision waits for it. */
        final AtomicBoolean pending = new AtomicBoolean();
        volatile ScheduledFuture<?> task;
        volatile String lastRefusal;

        Running(UUID id, String type, PlayerKeys keys, BotBrain brain, Mode mode) {
            this.id = id;
            this.type = type;
            this.keys = keys;
            this.brain = brain;
            this.mode = mode;
            this.offset = nextOffset.getAndIncrement();
        }
    }

    // ─────────── starting and stopping ───────────

    public synchronized void start() throws IOException {
        MarketState state = server.state();
        if (!state.botsEnabled()) throw new IOException("this market has no bots");
        // The market recorded which key its bots answer to when they were switched on. A
        // server whose identity key has since changed signs nothing any replica will accept,
        // so it says so once here rather than once per refused event.
        if (!state.botAuthorityKey().equals(server.hostKeys().publicKeyString())) {
            throw new IOException("this market's bots answer to a different key than this"
                    + " server's identity key (server-identity.key) — was it replaced?");
        }
        Files.createDirectories(dir);
        loadModes();

        ticker = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "bot-ticker"));
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newScheduledThreadPool(timing.poolSize,
                r -> daemon(r, "bot-pool-" + n.incrementAndGet()));

        int resumed = 0;
        for (Map.Entry<UUID, MarketState.BotRecord> e : state.bots().entrySet()) {
            if (run(e.getKey(), e.getValue()) && !e.getValue().paused()) resumed++;
        }
        ticker.scheduleAtFixedRate(this::onTick, timing.tickMs, timing.tickMs, TimeUnit.MILLISECONDS);
        System.out.println("[bots] running " + resumed + " of " + state.bots().size() + " bot"
                + (state.bots().size() == 1 ? "" : "s") + " (type 'bot help' for commands)");
    }

    public synchronized void stop() {
        if (ticker != null) ticker.shutdownNow();
        if (pool != null) pool.shutdownNow();
        ticker = null;
        pool = null;
        running.clear();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    /** Takes on a bot the market already has. False if it cannot be run from this machine. */
    private boolean run(UUID id, MarketState.BotRecord record) {
        String name = server.state().botName(id);
        PlayerKeys keys;
        try {
            keys = PlayerKeys.load(keyFile(id));
        } catch (Exception e) {
            System.err.println("[bots] " + name + " cannot act: its key file "
                    + keyFile(id).getFileName() + " is missing or unreadable");
            return false;
        }
        BotBrain brain = BotBrain.forType(record.type, record.algorithmVersion);
        if (brain == null) {
            System.err.println("[bots] " + name + " cannot act: this version of the mod has no "
                    + record.type + " algorithm version " + record.algorithmVersion);
            return false;
        }
        Mode mode = Mode.parse(modes.get(id.toString()));
        Running r = new Running(id, record.type, keys, brain, mode == null ? Mode.TICK : mode);
        running.put(id, r);
        if (!record.paused()) schedule(r);
        return true;
    }

    private void schedule(Running r) {
        if (r.mode != Mode.THREADS || pool == null) return;   // tick mode is driven by onTick
        long every = timing.every(r.type) * timing.tickMs;
        long first = (r.offset * 997L) % every + 1;           // spread out, not all at once
        r.task = pool.scheduleWithFixedDelay(() -> safely(() -> act(r)), first, every,
                TimeUnit.MILLISECONDS);
    }

    private void unschedule(Running r) {
        ScheduledFuture<?> t = r.task;
        if (t != null) t.cancel(false);
        r.task = null;
    }

    /** A scheduled task that throws is silently never run again, so nothing is allowed to. */
    private static void safely(Runnable work) {
        try {
            work.run();
        } catch (Throwable t) {
            System.err.println("[bots] " + t);
        }
    }

    // ─────────── the tick ───────────

    private void onTick() {
        safely(() -> {
            long t = tick.incrementAndGet();
            if (t % timing.productionEvery == 0) queueProduction();
            for (Running r : running.values()) {
                if (r.mode == Mode.TICK && (t + r.offset) % timing.every(r.type) == 0) act(r);
            }
        });
    }

    /** One decision: read the market, ask the brain, keep to the budget, queue the batch. */
    private void act(Running r) {
        if (r.pending.get()) return;

        MarketState state = server.state();
        List<Event.BotAction> actions;
        UUID marketId;
        Lock read = state.readLock();
        read.lock();
        try {
            MarketState.BotRecord record = state.bot(r.id);
            if (record == null || record.paused()) return;
            marketId = state.marketId();
            actions = r.brain.tick(new BotBrain.BotView(r.id, state, r.memory, tick.get()));
        } finally {
            read.unlock();
        }
        if (actions == null || actions.isEmpty()) return;

        int wanted = Math.min(actions.size(), BotRules.MAX_ACTIONS_PER_BATCH);
        int allowed = r.budget.take(wanted, timing.clock.getAsLong());
        if (allowed == 0) return;

        Event.BotActions batch = new Event.BotActions();
        batch.userId = r.id;
        batch.marketId = marketId;
        batch.clientEventId = UUID.randomUUID().toString();
        batch.timestamp = timing.clock.getAsLong();
        batch.actions = new ArrayList<>(actions.subList(0, allowed));
        String signature;
        try {
            signature = r.keys.sign(EventCanonical.canonicalPayload(batch));
        } catch (GeneralSecurityException e) {
            report(r, "could not sign: " + e.getMessage());
            return;
        }

        r.pending.set(true);
        boolean queued = server.enqueueHostWork(() -> {
            try {
                String why = server.appendSignedEvent(batch, signature);
                if (why != null) report(r, why);
                else r.lastRefusal = null;
            } catch (IOException e) {
                report(r, "could not write: " + e.getMessage());
            } finally {
                r.pending.set(false);
            }
        });
        if (!queued) r.pending.set(false);   // the sequencer is backed up; try again next time
    }

    /** Says why a bot's batch was refused, but not the same reason over and over. */
    private void report(Running r, String why) {
        if (why.equals(r.lastRefusal)) return;
        r.lastRefusal = why;
        System.out.println("[bots] " + server.state().botName(r.id) + ": " + why);
    }

    // ─────────── production ───────────

    /**
     * Puts one production pass on the sequencer, unless one is already waiting.
     *
     * The pass works out what is owed on the sequencer thread itself, from the state as it
     * is then, so a pass that sat in the queue cannot produce against a stale picture, and
     * two passes can never both claim the same stretch of time.
     */
    private void queueProduction() {
        if (!productionQueued.compareAndSet(false, true)) return;
        boolean queued = server.enqueueHostWork(() -> {
            try {
                produceOwed();
            } finally {
                productionQueued.set(false);
            }
        });
        if (!queued) productionQueued.set(false);
    }

    /** Sequencer thread only, which is what makes reading the state here without a lock safe. */
    private void produceOwed() {
        MarketState state = server.state();
        long now = timing.clock.getAsLong();
        for (Map.Entry<UUID, MarketState.BotRecord> e : state.bots().entrySet()) {
            UUID id = e.getKey();
            MarketState.BotRecord record = e.getValue();
            if (record.paused()) continue;
            for (String item : BotRules.ITEMS) {
                if (!BotRules.produces(record.type, item)) continue;
                long owed = BotRules.productionAllowance(now - record.lastProducedAt(item));
                if (owed < BotRules.PRODUCTION_LOT) continue;
                long holding = state.itemBalances().getBalance(id, item)
                        + state.restingVolume(id, item, false);
                long amount = Math.min(owed, BotRules.PRODUCTION_CEILING - holding);
                if (amount <= 0) continue;

                Event.BotProduced bp = new Event.BotProduced();
                bp.userId = id;
                bp.itemId = item;
                bp.amount = amount;
                String why = appendAsAuthority(bp, now);
                if (why != null) {
                    System.out.println("[bots] " + state.botName(id) + " could not produce "
                            + item + ": " + why);
                }
            }
        }
    }

    /** Signs with the server key, as the bots' authority, and appends. Sequencer thread only. */
    private String appendAsAuthority(Event e, long now) {
        e.marketId = server.state().marketId();
        e.clientEventId = UUID.randomUUID().toString();
        e.timestamp = now;
        try {
            return server.appendSignedEvent(e,
                    server.hostKeys().sign(EventCanonical.canonicalPayload(e)));
        } catch (GeneralSecurityException ex) {
            return "could not sign: " + ex.getMessage();
        } catch (IOException ex) {
            return "could not write: " + ex.getMessage();
        }
    }

    // ─────────── console commands ───────────

    /**
     * Spawns count bots of a type on a scheduler. The answer arrives through reply, from the
     * sequencer thread, once they are in the log.
     */
    public void spawn(String type, int count, Mode mode, Consumer<String> reply) {
        boolean queued = server.enqueueHostWork(() -> {
            for (int i = 0; i < count; i++) {
                String outcome;
                try {
                    outcome = spawnOne(type, mode);
                } catch (Exception e) {
                    outcome = "could not spawn a " + type + ": " + e.getMessage();
                }
                reply.accept(outcome);
                if (outcome.startsWith("could not")) break;
            }
        });
        if (!queued) reply.accept("the server is too busy right now, try again");
    }

    /** Sequencer thread only. */
    private String spawnOne(String type, Mode mode) throws Exception {
        MarketState state = server.state();
        if (state.botCount(type) >= BotRules.MAX_PER_TYPE) {
            return "could not spawn a " + type + ": this market already has "
                    + BotRules.MAX_PER_TYPE + ", the most it can have";
        }
        int version = BotBrain.currentVersion(type);
        BotBrain brain = BotBrain.forType(type, version);
        if (brain == null) return "could not spawn a " + type + ": no algorithm for it";

        UUID id = UUID.randomUUID();
        PlayerKeys keys = PlayerKeys.generate(PlayerKeys.Algorithm.ED25519);
        // The key is on disk before the bot is in the log, so a crash between the two leaves
        // a key file with no bot rather than a bot nobody can ever act for.
        Files.createDirectories(dir);
        keys.saveTo(keyFile(id));

        // Registered with its own key, the way any identity joins. Not through the path a
        // player's registration takes, which follows it with a welcome grant: a bot starts
        // with its fixed grant and nothing else.
        Event.KeyRegistered kr = new Event.KeyRegistered();
        kr.userId = id;
        kr.publicKey = keys.publicKeyString();
        kr.marketId = state.marketId();
        kr.clientEventId = UUID.randomUUID().toString();
        kr.timestamp = timing.clock.getAsLong();
        String why = server.appendSignedEvent(kr, keys.sign(EventCanonical.canonicalPayload(kr)));
        if (why != null) {
            Files.deleteIfExists(keyFile(id));
            return "could not spawn a " + type + ": " + why;
        }

        Event.BotSpawned bs = new Event.BotSpawned();
        bs.userId = id;
        bs.botType = type;
        bs.algorithmVersion = version;
        why = appendAsAuthority(bs, timing.clock.getAsLong());
        if (why != null) return "could not spawn a " + type + ": " + why;

        modes.put(id.toString(), mode.word());
        saveModes();
        Running r = new Running(id, type, keys, brain, mode);
        running.put(id, r);
        schedule(r);
        return "spawned " + state.botName(id) + " (" + shortId(id) + ") on the "
                + mode.word() + " scheduler";
    }

    /** Pauses a bot: its orders come off the book and it stops deciding until resumed. */
    public void pause(UUID id, Consumer<String> reply) {
        authorityCommand(id, new Event.BotPaused(), reply, () -> {
            Running r = running.get(id);
            if (r != null) unschedule(r);
            return "paused " + server.state().botName(id) + " — its orders are off the book";
        });
    }

    public void resume(UUID id, Consumer<String> reply) {
        authorityCommand(id, new Event.BotResumed(), reply, () -> {
            Running r = running.get(id);
            if (r == null) {
                return "resumed " + server.state().botName(id)
                        + " in the market, but this server cannot run it (see the start-up message)";
            }
            schedule(r);
            return "resumed " + server.state().botName(id);
        });
    }

    private void authorityCommand(UUID id, Event e, Consumer<String> reply,
                                  java.util.function.Supplier<String> after) {
        e.userId = id;
        boolean queued = server.enqueueHostWork(() -> {
            String why = appendAsAuthority(e, timing.clock.getAsLong());
            reply.accept(why == null ? after.get() : server.state().botName(id) + ": " + why);
        });
        if (!queued) reply.accept("the server is too busy right now, try again");
    }

    /** One line per bot, for the console. Taken under the read lock, so it is one moment. */
    public List<String> describe() {
        MarketState state = server.state();
        List<String> out = new ArrayList<>();
        Lock read = state.readLock();
        read.lock();
        try {
            List<Map.Entry<UUID, MarketState.BotRecord>> bots = new ArrayList<>(state.bots().entrySet());
            bots.sort(Comparator.comparing((Map.Entry<UUID, MarketState.BotRecord> e) -> e.getValue().type)
                    .thenComparingInt(e -> e.getValue().number));
            for (Map.Entry<UUID, MarketState.BotRecord> e : bots) {
                UUID id = e.getKey();
                MarketState.BotRecord rec = e.getValue();
                Running r = running.get(id);
                String status = rec.paused() ? "paused"
                        : r == null ? "not running here" : "running (" + r.mode.word() + ")";
                StringBuilder held = new StringBuilder();
                for (String item : BotRules.ITEMS) {
                    long n = state.itemBalances().getBalance(id, item) + state.restingVolume(id, item, false);
                    held.append(' ').append(item.substring(item.indexOf(':') + 1)).append('=').append(n);
                }
                out.add(String.format(Locale.ROOT, "%-16s %s  %-22s %d credits, %d orders,%s",
                        state.botName(id), shortId(id), status, state.wallets().getBalance(id),
                        state.restingOrdersOf(id).size(), held));
            }
        } finally {
            read.unlock();
        }
        return out;
    }

    /**
     * Finds a bot from what an operator typed: a full id, the start of one, or a name like
     * producer#2 (which is how "Producer #2" reads with the space taken out).
     */
    public UUID resolve(String typed) {
        MarketState state = server.state();
        String want = typed.toLowerCase(Locale.ROOT).replace(" ", "");
        UUID found = null;
        for (Map.Entry<UUID, MarketState.BotRecord> e : state.bots().entrySet()) {
            UUID id = e.getKey();
            String name = state.botName(id).toLowerCase(Locale.ROOT).replace(" ", "");
            String shortName = e.getValue().type + "#" + e.getValue().number;
            if (want.equals(name) || want.equals(shortName) || id.toString().equals(want)) return id;
            if (want.length() >= 4 && id.toString().startsWith(want)) {
                if (found != null) return null;   // the prefix is not unique
                found = id;
            }
        }
        return found;
    }

    // ─────────── files ───────────

    private Path keyFile(UUID id) {
        return dir.resolve(id + ".key");
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private void loadModes() {
        if (!Files.exists(registryFile)) return;
        try {
            Map<String, String> saved = gson.fromJson(
                    new String(Files.readAllBytes(registryFile), StandardCharsets.UTF_8),
                    new TypeToken<Map<String, String>>() {}.getType());
            if (saved != null) modes.putAll(saved);
        } catch (Exception e) {
            // Only which scheduler each bot was on. Losing it puts them all on the tick,
            // which is a fine place to be — no reason to refuse to start over it.
            System.err.println("[bots] could not read " + registryFile.getFileName()
                    + ", every bot will run on the tick scheduler: " + e.getMessage());
        }
    }

    private void saveModes() throws IOException {
        Path tmp = registryFile.resolveSibling(registryFile.getFileName() + ".tmp");
        Files.write(tmp, gson.toJson(new TreeMap<>(modes)).getBytes(StandardCharsets.UTF_8));
        Files.move(tmp, registryFile, StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    // ─────────── the action budget ───────────

    /**
     * How many actions a bot may send: a bucket that holds capacity and gains one every
     * refillMs. What a brain asks for past it is dropped, not saved for later — the next
     * decision is made against the market as it is then, which is the one that matters.
     *
     * The runner's own limit, not a market rule. It keeps a misbehaving brain from filling the
     * log; replicas have the per-batch cap in BotRules to rely on instead.
     */
    static final class Budget {
        private final int capacity;
        private final long refillMs;
        private long tokens;
        private long refilledAt;

        Budget(int capacity, long refillMs, long now) {
            this.capacity = capacity;
            this.refillMs = refillMs;
            this.tokens = capacity;
            this.refilledAt = now;
        }

        synchronized int take(int wanted, long now) {
            if (now > refilledAt) {
                long gained = (now - refilledAt) / refillMs;
                if (gained > 0) {
                    tokens = Math.min(capacity, tokens + gained);
                    refilledAt += gained * refillMs;
                }
                if (tokens == capacity) refilledAt = now;   // a full bucket does not bank time
            }
            int n = (int) Math.min(wanted, tokens);
            tokens -= n;
            return n;
        }
    }
}
