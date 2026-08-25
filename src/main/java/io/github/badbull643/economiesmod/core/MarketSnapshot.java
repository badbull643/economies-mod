package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * A market's state, written down beside its log so the next load does not have to
 * rebuild it from the beginning.
 *
 * <h3>What this is allowed to be, and what it is not</h3>
 *
 * This is option A of {@code docs/design/log-compaction.md}: <b>self-computed, never
 * shared.</b> A replica writes only state it derived itself, from a chain it verified
 * itself, and reads back only its own file. No host asserts a balance to anybody, so the
 * invariant the signed chain exists for is untouched. A snapshot that arrived over a
 * network would be option B, which is refused — do not add a way to send one.
 *
 * <h3>Why skipping the verification of the prefix is safe</h3>
 *
 * The file records the chain hash at the sequence number it was taken at, and is used
 * only if the log still carries that hash there. Two things can go wrong with a log and
 * both come out right:
 *
 * <ul>
 *   <li><b>Somebody rewrites history and re-chains it.</b> Every hash from the edit
 *       onwards changes, so the hash at our sequence number no longer matches, the
 *       snapshot is discarded, and the log is replayed and verified in full.</li>
 *   <li><b>Somebody edits one entry and does not re-chain it.</b> The hash at our
 *       sequence number is unchanged, so the snapshot is used — and that is the right
 *       answer, because the state in it was computed before the edit, from the log as it
 *       was when it verified. The tampered entry is never read.</li>
 * </ul>
 *
 * A log that has been truncated below the snapshot point answers with no hash at all,
 * which is also a discard. There is no case where a stale or doctored prefix reaches
 * the restored state.
 *
 * <h3>The field this class forgets</h3>
 *
 * The real risk here is not cryptographic, it is clerical: {@link MarketState} gains
 * fields, and a serialiser that was complete last month quietly stops being complete.
 * That is not hypothetical — it held thirteen fields on 2026-08-16 and twenty-one seven
 * days later, and one of the eight added is the set that stops a migration being cashed
 * in twice.
 *
 * So nothing here relies on anybody remembering. {@link #shapeFingerprint()} reflects
 * over the declared fields of {@code MarketState} and the state classes it holds, and
 * the fingerprint is written into the file. A snapshot whose fingerprint does not match
 * this build is discarded unread. Add a field and every existing snapshot becomes a slow
 * load, which is the failure everybody can afford.
 *
 * The fingerprint is not total. A field whose <i>meaning</i> changes without its name or
 * type changing looks identical to it, and so does a change in how an existing field is
 * computed. The round-trip test in {@code coreTests} L10 is what covers those: it
 * compares every observable of a restored market against a full replay of the same log.
 */
public final class MarketSnapshot {

    /**
     * Below this many events a snapshot is not worth its own risk — the replay it saves
     * is a few milliseconds, and every snapshot is one more thing that can be stale.
     */
    private static long minEvents = 5000;

    /**
     * How far the log may run past a snapshot before a new one is written. Also the
     * worst-case tail a load ever replays, which at measured speed is a few tens of
     * milliseconds.
     */
    private static long stride = 5000;

    public static long minEvents() { return minEvents; }
    public static long stride() { return stride; }

    /**
     * Moves the thresholds, for a test that wants to watch a snapshot actually appear,
     * and for trying the feature out on a market smaller than a real one.
     *
     * The alternative is a test that builds five thousand signed events to see one file
     * get written, which is slow enough that nobody would keep it — and the defect this
     * covers was precisely a threshold that turned out to decide nothing, on a market
     * deliberately shrunk to look at it. Restore what this returns in a finally.
     */
    static long[] thresholdsForTesting(long min, long str) {
        long[] was = { minEvents, stride };
        minEvents = min;
        stride = str;
        return was;
    }

    /**
     * Whether a snapshot is worth writing, given where the log ends and where the
     * snapshot beside it already sits ({@code existingSeq <= 0} for none).
     *
     * The two thresholds answer different questions and this is the only place that
     * knows it. {@link #minEvents()} decides when a market is long enough to be worth
     * snapshotting at all; {@link #stride()} decides how far the log may drift past a
     * snapshot that already exists. Testing the first write against STRIDE — which is
     * what the caller used to do — makes MIN_EVENTS decide nothing at all, and is
     * invisible while the two constants happen to be equal.
     *
     * That was here for exactly one session and was found by somebody lowering
     * MIN_EVENTS to try the feature out and getting no snapshot and no explanation.
     */
    static boolean worthWriting(long headSeq, long existingSeq) {
        if (headSeq < minEvents) return false;
        if (existingSeq <= 0) return true;
        return headSeq - existingSeq >= stride;
    }

    /** The file that belongs beside a given log. */
    public static Path pathFor(EventLog log) {
        return log.file().resolveSibling(log.file().getFileName() + ".snapshot.json");
    }

    private static final Gson GSON = new GsonBuilder().create();

    // ─────────── the file ───────────

    /** Everything on disk. Plain types only — nothing here is an interface Gson has to guess. */
    static final class Body {
        String shape;
        long seq;
        String chainHash;

        /**
         * Whether this was written by a replica that deliberately keeps no history.
         *
         * The difference between "there is no log because I chose not to keep one" and
         * "there is no log because it went missing", and the whole reason a snapshot
         * with no log beside it can be trusted at all. Without it, deleting a market's
         * log stopped resetting the market — the snapshot beside it would be taken on
         * its own authority and hand the old market straight back, which is the opposite
         * of what deleting a log has always meant.
         *
         * Absent in a file written before this existed, and Gson leaves a missing
         * boolean false, which is the safe answer: an old snapshot needs its log.
         */
        boolean logless;

        String marketId;
        String marketName;
        String creator;

        int taxBps;
        long welcomeGrant;
        long listingFee;
        int listingFreeOrders;
        long stipendAmount;
        long stipendEveryFills;
        long fillsEver;

        Map<String, String> keyDirectory = new LinkedHashMap<>();
        List<String> granted = new ArrayList<>();
        List<String> migrationsDone = new ArrayList<>();
        List<String> accountedElsewhere = new ArrayList<>();
        List<String> migratedIn = new ArrayList<>();
        Map<String, Long> stipendedAtFill = new LinkedHashMap<>();
        Map<String, Long> withdrawn = new LinkedHashMap<>();
        Map<String, Long> wallets = new LinkedHashMap<>();
        Map<String, Map<String, Long>> itemBalances = new LinkedHashMap<>();
        Map<String, BookBody> books = new LinkedHashMap<>();
        Map<String, List<TradeBody>> trades = new LinkedHashMap<>();

        /**
         * The host rules this market's creator published, stored as the event itself.
         *
         * Every field on it is already a plain boxed type, so there is nothing for Gson
         * to guess, and keeping the whole event keeps its provenance — who published it
         * and when — rather than flattening it into values that could have come from
         * anywhere.
         */
        Event.HostDefaults hostDefaults;
    }

    /**
     * One book. Price levels are written as a list rather than a map because order
     * matters twice over: the level order is the book's, and the order within a level is
     * the queue that decides who fills first. A JSON object would hand both to whatever
     * the reader felt like doing.
     */
    static final class BookBody {
        List<LevelBody> asks = new ArrayList<>();
        List<LevelBody> bids = new ArrayList<>();
    }

    static final class LevelBody {
        long price;
        List<OrderBody> orders = new ArrayList<>();
    }

    static final class OrderBody {
        long orderId;
        long price;
        long volume;
        String itemId;
        boolean isBid;
        String userId;
    }

    static final class TradeBody {
        long seq;
        long timestamp;
        String itemId;
        long price;
        long quantity;
        String buyerId;
        String sellerId;
    }

    // ─────────── writing ───────────

    /**
     * Writes the snapshot for a state, replacing any previous one.
     *
     * Written to a temporary file and moved into place, because a half-written snapshot
     * that still parses is exactly the kind of thing this class must not produce. A move
     * that cannot be atomic on this filesystem still beats writing in place.
     */
    public static void save(EventLog log, MarketState state, long seq, String chainHash)
            throws IOException {
        save(log, state, seq, chainHash, false);
    }

    /**
     * The same, for a replica that keeps no history and needs this to be its memory.
     *
     * Says so in the file, because a snapshot with no log beside it is only trustworthy
     * when the missing log was a decision. See Body.logless.
     */
    public static void save(EventLog log, MarketState state, long seq, String chainHash,
                            boolean logless) throws IOException {
        Path target = pathFor(log);
        Body b = capture(state, seq, chainHash);
        b.logless = logless;

        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        try (java.io.BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(b, w);
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Body capture(MarketState state, long seq, String chainHash) {
        Body b = new Body();
        b.shape = shapeFingerprint();
        b.seq = seq;
        b.chainHash = chainHash;

        b.marketId = str(state.marketId());
        b.marketName = state.marketName();
        b.creator = str(state.creator());

        b.taxBps = state.taxBps();
        b.welcomeGrant = state.welcomeGrant();
        b.listingFee = state.listingFee();
        b.listingFreeOrders = state.listingFreeOrders();
        b.stipendAmount = state.stipendAmount();
        b.stipendEveryFills = state.stipendEveryFills();
        b.fillsEver = state.fillsEver();
        b.hostDefaults = state.hostDefaults();

        for (Map.Entry<UUID, String> e : state.keyDirectory().entrySet()) {
            b.keyDirectory.put(str(e.getKey()), e.getValue());
        }
        for (UUID u : state.granted()) b.granted.add(str(u));
        b.migrationsDone.addAll(state.migrationsDone());
        for (UUID u : state.accountedElsewhere()) b.accountedElsewhere.add(str(u));
        for (UUID u : state.migratedIn()) b.migratedIn.add(str(u));
        for (Map.Entry<UUID, Long> e : state.stipendedAtFills().entrySet()) {
            b.stipendedAtFill.put(str(e.getKey()), e.getValue());
        }
        b.withdrawn.putAll(state.withdrawn());

        for (Map.Entry<UUID, Long> e : state.wallets().balances().entrySet()) {
            b.wallets.put(str(e.getKey()), e.getValue());
        }
        for (Map.Entry<UUID, Map<String, Long>> e : state.itemBalances().balances().entrySet()) {
            b.itemBalances.put(str(e.getKey()), new LinkedHashMap<>(e.getValue()));
        }

        for (Map.Entry<String, OrderBook> e : state.markets().entrySet()) {
            BookBody book = new BookBody();
            book.asks = levels(e.getValue().asks());
            book.bids = levels(e.getValue().bids());
            b.books.put(e.getKey(), book);
        }

        for (Map.Entry<String, Deque<Trade>> e : state.trades().byItem().entrySet()) {
            List<TradeBody> out = new ArrayList<>();
            for (Trade t : e.getValue()) {
                TradeBody tb = new TradeBody();
                tb.seq = t.seq;
                tb.timestamp = t.timestamp;
                tb.itemId = t.itemId;
                tb.price = t.price;
                tb.quantity = t.quantity;
                tb.buyerId = str(t.buyerId);
                tb.sellerId = str(t.sellerId);
                out.add(tb);
            }
            b.trades.put(e.getKey(), out);
        }
        return b;
    }

    private static List<LevelBody> levels(TreeMap<Long, Deque<Order>> side) {
        List<LevelBody> out = new ArrayList<>();
        for (Map.Entry<Long, Deque<Order>> e : side.entrySet()) {
            LevelBody lb = new LevelBody();
            lb.price = e.getKey();
            for (Order o : e.getValue()) {
                OrderBody ob = new OrderBody();
                ob.orderId = o.orderId();
                ob.price = o.value();
                ob.volume = o.volume();
                ob.itemId = o.itemID();
                ob.isBid = o.isBid();
                ob.userId = str(o.userID());
                lb.orders.add(ob);
            }
            out.add(lb);
        }
        return out;
    }

    // ─────────── reading ───────────

    /**
     * The market name recorded in the snapshot beside this log, or null.
     *
     * Reads the header and rebuilds nothing, because the one caller is the market
     * switcher asking what to write on a row. It exists because a slot that keeps a
     * snapshot and no history has no genesis event to take a name from, so the switcher
     * showed it as nothing at all — a row you cannot identify, in the control §0.16 was
     * written about.
     *
     * Deliberately not checked against the chain. A name is not a balance: the worst a
     * stale one can do is label a row, and refusing to label it is worse than labelling
     * it from a file that turns out to be out of date.
     */
    public static String marketNameFor(EventLog log) {
        try {
            Path file = pathFor(log);
            if (!Files.exists(file)) return null;
            try (java.io.BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Body b = GSON.fromJson(r, Body.class);
                return b == null ? null : b.marketName;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The market id recorded in the snapshot beside this log, or null.
     *
     * Same reasoning as {@link #marketNameFor}: a slot keeping a snapshot and no history
     * has no genesis event to read an identity from, and "which market is in this slot"
     * is a question the world has to be able to answer about every slot — otherwise two
     * slots can end up holding the same market without anything noticing.
     */
    public static UUID marketIdFor(EventLog log) {
        try {
            Path file = pathFor(log);
            if (!Files.exists(file)) return null;
            try (java.io.BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                Body b = GSON.fromJson(r, Body.class);
                return b == null || b.marketId == null ? null : UUID.fromString(b.marketId);
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** A snapshot that was found, checked against the log, and rebuilt. */
    public static final class Restored {
        public final MarketState state;
        public final long seq;
        public final String chainHash;

        /**
         * Whether this was taken on its own authority, with no log beside it to check.
         *
         * The one thing a caller cannot work out afterwards without reading the file,
         * and the whole of what {@code logCoversHead} needs to know: a snapshot accepted
         * against the log's own hash has a log under it by definition, and only this
         * branch produces state the log does not account for. Carried here so that
         * question costs nothing — it used to be answered by walking the whole file,
         * which is what a snapshot exists to avoid.
         */
        public final boolean logless;

        Restored(MarketState state, long seq, String chainHash, boolean logless) {
            this.state = state;
            this.seq = seq;
            this.chainHash = chainHash;
            this.logless = logless;
        }
    }

    /**
     * The snapshot beside this log, if there is one and it still describes this log.
     *
     * Returns null for every kind of no — absent, unparseable, written by a build whose
     * MarketState was a different shape, or naming a chain this log no longer has. The
     * caller replays from the beginning in all of them, which is the slow answer and
     * never the wrong one.
     */
    /**
     * Removes a snapshot that can never be valid again.
     *
     * Only for the two answers that cannot change by themselves: a file this build cannot
     * parse, and one written against a different {@code MarketState} shape. Neither comes
     * back — a shape is a property of the build, so short of downgrading the mod that file
     * is dead weight the loader will keep reading and keep refusing.
     *
     * Not for the chain-hash cases below. "This log no longer has that chain" is a
     * statement about the log, and a log can be restored from a backup or a slot copied
     * back into place, so deleting there would throw away something that might yet be
     * usable. The rule is: delete what this build can never use, keep what this *log* is
     * currently not matching.
     *
     * The nuisance this fixes is small and the reason it is worth fixing is not. A market
     * below {@code minEvents} never writes a replacement, so the stale file stays and the
     * "being discarded" line prints on every single load, forever — a message that says
     * something changed, printed when nothing has. Five in ten minutes in one play
     * session's log. That is how a console line people should read becomes one they skim.
     */
    private static void discard(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            // Never worth failing a load over. The snapshot is already not being used;
            // the only cost of leaving it is the message printing again next time.
            System.err.println("[economiesmod] could not remove the stale snapshot "
                    + file.getFileName() + ": " + e.getMessage());
        }
    }

    public static Restored loadIfValid(EventLog log) {
        Path file = pathFor(log);
        try {
            if (!Files.exists(file)) return null;

            Body b;
            try (java.io.BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                b = GSON.fromJson(r, Body.class);
            } catch (Exception unparseable) {
                System.err.println("[economiesmod] snapshot unreadable, replaying in full: "
                        + unparseable.getMessage());
                discard(file);
                return null;
            }
            if (b == null || b.chainHash == null || b.seq <= 0) {
                discard(file);
                return null;
            }

            if (!shapeFingerprint().equals(b.shape)) {
                System.out.println("[economiesmod] snapshot was written for a different"
                        + " MarketState and is being discarded — replaying in full."
                        + " This is what is supposed to happen when a field is added.");
                discard(file);
                return null;
            }

            String onDisk = log.hashAtSeqFast(b.seq);
            if (onDisk == null && b.logless && log.headSeqOnDisk() == 0) {
                // No log at all beside it. That is a client of a dedicated market, which
                // keeps a snapshot and does not archive the history — see the persist
                // rule in MarketClient. There is nothing here to contradict the snapshot
                // and nothing to check it against, so it is taken on the authority it
                // was written with: this replica computed it, from a chain it verified.
                //
                // The check does not disappear, it moves. The handshake sends our head
                // sequence and hash, and a host whose chain says otherwise refuses us or
                // reports a fork. So a snapshot that has drifted from the market is
                // caught the moment we next speak to anybody — which for a market with
                // no local history is the only moment it could matter.
                return new Restored(rebuild(b), b.seq, b.chainHash, true);
            }
            if (onDisk == null || !onDisk.equals(b.chainHash)) {
                System.out.println("[economiesmod] snapshot describes a chain this log no"
                        + " longer has — replaying in full");
                return null;
            }

            return new Restored(rebuild(b), b.seq, b.chainHash, false);
        } catch (Exception e) {
            // A snapshot must never be able to stop a market opening. Every failure here
            // is answerable by doing the thing this class exists to avoid.
            System.err.println("[economiesmod] snapshot could not be used, replaying in"
                    + " full: " + e);
            return null;
        }
    }

    static MarketState rebuild(Body b) {
        MarketState s = new MarketState();

        if (b.marketId != null) {
            s.setMarketIdentity(uuid(b.marketId), b.marketName, uuid(b.creator));
        }
        s.setTaxBps(b.taxBps);
        s.setWelcomeGrant(b.welcomeGrant);
        s.setListingFee(b.listingFee);
        s.setListingFreeOrders(b.listingFreeOrders);
        s.setStipend(b.stipendAmount, b.stipendEveryFills);
        s.setFillsEver(b.fillsEver);
        s.setHostDefaults(b.hostDefaults);

        if (b.keyDirectory != null) {
            for (Map.Entry<String, String> e : b.keyDirectory.entrySet()) {
                s.keyDirectory().put(uuid(e.getKey()), e.getValue());
            }
        }
        for (String u : orEmpty(b.granted)) s.granted().add(uuid(u));
        s.migrationsDone().addAll(orEmpty(b.migrationsDone));
        for (String u : orEmpty(b.accountedElsewhere)) s.accountedElsewhere().add(uuid(u));
        for (String u : orEmpty(b.migratedIn)) s.migratedIn().add(uuid(u));
        if (b.stipendedAtFill != null) {
            for (Map.Entry<String, Long> e : b.stipendedAtFill.entrySet()) {
                s.stipendedAtFills().put(uuid(e.getKey()), e.getValue());
            }
        }
        if (b.withdrawn != null) s.withdrawn().putAll(b.withdrawn);

        if (b.wallets != null) {
            for (Map.Entry<String, Long> e : b.wallets.entrySet()) {
                s.wallets().balances().put(uuid(e.getKey()), e.getValue());
            }
        }
        if (b.itemBalances != null) {
            for (Map.Entry<String, Map<String, Long>> e : b.itemBalances.entrySet()) {
                s.itemBalances().balances()
                        .put(uuid(e.getKey()), new java.util.HashMap<>(e.getValue()));
            }
        }

        if (b.books != null) {
            for (Map.Entry<String, BookBody> e : b.books.entrySet()) {
                OrderBook book = s.bookFor(e.getKey());
                restoreLevels(book.asks(), e.getValue().asks);
                restoreLevels(book.bids(), e.getValue().bids);
            }
        }

        if (b.trades != null) {
            for (Map.Entry<String, List<TradeBody>> e : b.trades.entrySet()) {
                Deque<Trade> q = new ArrayDeque<>();
                for (TradeBody tb : e.getValue()) {
                    q.addLast(new Trade(tb.seq, tb.timestamp, tb.itemId, tb.price,
                            tb.quantity, uuid(tb.buyerId), uuid(tb.sellerId)));
                }
                s.trades().byItem().put(e.getKey(), q);
            }
        }
        return s;
    }

    /**
     * Puts orders back exactly where they were.
     *
     * Never through {@code OrderBook.submit} — that is the matching engine, and running
     * a resting book back through it would cross orders against each other that never
     * crossed, inventing fills nobody made. The queue order within a price level is
     * preserved because it is the order people will be filled in.
     */
    private static void restoreLevels(TreeMap<Long, Deque<Order>> side, List<LevelBody> levels) {
        if (levels == null) return;
        for (LevelBody lb : levels) {
            Deque<Order> q = new ArrayDeque<>();
            for (OrderBody ob : lb.orders) {
                q.addLast(new Order(ob.orderId, ob.price, ob.itemId, ob.volume,
                        ob.isBid, uuid(ob.userId)));
            }
            side.put(lb.price, q);
        }
    }

    // ─────────── the shape fingerprint ───────────

    private static volatile String cachedShape;

    /**
     * A short hash of what {@link MarketState} is made of, right now, in this build.
     *
     * Walks the declared fields of MarketState and of every state class it holds that
     * belongs to this package, recording each field's name and full generic type. Any
     * field added, removed, renamed or retyped anywhere in that graph changes the answer,
     * and every snapshot written under the old answer is discarded on sight.
     *
     * This exists because the alternative is a version number somebody has to remember
     * to change in a second place, which is the defect this project keeps finding in
     * itself. Here there is no second place to forget.
     */
    public static String shapeFingerprint() {
        String cached = cachedShape;
        if (cached != null) return cached;

        List<String> lines = new ArrayList<>();
        java.util.Set<Class<?>> seen = new java.util.LinkedHashSet<>();
        describe(MarketState.class, lines, seen);
        Collections.sort(lines);

        StringBuilder joined = new StringBuilder();
        for (String l : lines) joined.append(l).append('\n');

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(joined.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((d[i] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(d[i] & 0xf, 16));
            }
            cachedShape = hex.toString();
            return cachedShape;
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void describe(Class<?> type, List<String> out, java.util.Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) return;
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;      // constants are not state
            out.add(type.getSimpleName() + "." + f.getName() + ":" + f.getGenericType());
            for (Class<?> nested : stateTypesIn(f)) describe(nested, out, seen);
        }
    }

    /**
     * The classes of ours that a field reaches, so the walk follows state into the
     * registries and the book rather than stopping at MarketState's own fields.
     */
    private static List<Class<?>> stateTypesIn(Field f) {
        List<Class<?>> out = new ArrayList<>();
        collectOurTypes(f.getGenericType(), out);
        return out;
    }

    private static void collectOurTypes(java.lang.reflect.Type t, List<Class<?>> out) {
        if (t instanceof Class<?>) {
            Class<?> c = (Class<?>) t;
            if (c.getName().startsWith("io.github.badbull643.economiesmod.core.")) out.add(c);
            return;
        }
        if (t instanceof java.lang.reflect.ParameterizedType) {
            java.lang.reflect.ParameterizedType p = (java.lang.reflect.ParameterizedType) t;
            collectOurTypes(p.getRawType(), out);
            for (java.lang.reflect.Type arg : p.getActualTypeArguments()) {
                collectOurTypes(arg, out);
            }
        }
    }

    /** The field list the fingerprint is taken over — for a test that wants to see it. */
    static List<String> shapeLines() {
        List<String> lines = new ArrayList<>();
        describe(MarketState.class, lines, new java.util.LinkedHashSet<>());
        Collections.sort(lines);
        return lines;
    }

    // ─────────── small helpers ───────────

    private static String str(UUID u) { return u == null ? null : u.toString(); }

    private static UUID uuid(String s) { return s == null ? null : UUID.fromString(s); }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? Collections.<T>emptyList() : l;
    }

    private MarketSnapshot() {}
}
