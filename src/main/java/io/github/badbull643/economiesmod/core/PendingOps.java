package io.github.badbull643.economiesmod.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * A durable note of a physical inventory operation that is only half done.
 *
 * The ledger and the player's actual inventory are two separate systems, and nothing
 * spans both transactionally. Every deposit and withdraw therefore has a window where
 * one has happened and the other has not, and a crash inside that window is invisible
 * afterwards — the items are simply gone, or simply never arrived, with nothing
 * anywhere recording that anything was owed.
 *
 * This is the missing record. It is written before the risky half and cleared after it,
 * so anything still here at startup is a window someone fell into.
 *
 * The two directions recover differently, because only one of them has an authority to
 * consult:
 *
 *  DEPOSIT  — items leave the inventory first, then the event is proposed. The log
 *             settles it exactly: if an event with this clientEventId is in there, the
 *             deposit landed and the ledger already holds the value; if it isn't, it
 *             never will, and the items can be safely returned. No ambiguity, no risk
 *             of returning items that were also credited.
 *
 *  WITHDRAW — the event is applied first, then items are handed over. Nothing records
 *             whether the hand-over completed, and Minecraft's inventory operations
 *             are not idempotent, so re-giving on the strength of a surviving record
 *             would mint items whenever the crash landed after the give rather than
 *             before it. A record that outlives a restart is therefore *reported*, not
 *             acted on. Losing items is a bad outcome; duplicating them is a worse one,
 *             and the rest of the trust model exists to stop exactly that.
 */
public class PendingOps {

    private static final Gson gson = new Gson();

    public static final String DEPOSIT = "DEPOSIT";
    public static final String WITHDRAW = "WITHDRAW";

    /** On-disk shape. Deliberately flat and stringly-typed: this file has to be
     *  readable by a build that no longer has the class that wrote it. */
    public static class Op {
        public String kind;
        public String userId;
        public String itemId;
        public long quantity;
        /** DEPOSIT only — the proposal we are waiting to see land in the log. */
        public String clientEventId;
        /** WITHDRAW only — the event that already debited the ledger. */
        public long seq;
        public long timestamp;

        public boolean isDeposit() { return DEPOSIT.equals(kind); }
        public boolean isWithdraw() { return WITHDRAW.equals(kind); }

        public String describe() {
            return quantity + " " + itemId;
        }
    }

    private final Path file;
    private List<Op> ops = new ArrayList<>();

    public PendingOps(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                List<Op> loaded = gson.fromJson(json,
                        new TypeToken<List<Op>>() {}.getType());
                if (loaded != null) ops = loaded;
            }
        } catch (Exception e) {
            // A damaged journal must not stop the world loading. Worst case we lose the
            // record of a half-finished operation, which is where we were before it existed.
            System.err.println("[economiesmod] could not read pending ops: " + e);
        }
    }

    /** Records that items are about to leave the inventory for this proposal. */
    public synchronized void recordDeposit(UUID userId, String clientEventId,
                                           String itemId, long quantity) {
        Op op = new Op();
        op.kind = DEPOSIT;
        op.userId = userId == null ? null : userId.toString();
        op.clientEventId = clientEventId;
        op.itemId = itemId;
        op.quantity = quantity;
        op.timestamp = System.currentTimeMillis();
        ops.add(op);
        save();
    }

    /** Records that the ledger has been debited and items are about to be handed over. */
    public synchronized void recordWithdraw(UUID userId, long seq,
                                            String itemId, long quantity) {
        Op op = new Op();
        op.kind = WITHDRAW;
        op.userId = userId == null ? null : userId.toString();
        op.seq = seq;
        op.itemId = itemId;
        op.quantity = quantity;
        op.timestamp = System.currentTimeMillis();
        ops.add(op);
        save();
    }

    /** Clears a deposit once its event is known to have landed. */
    public synchronized void clearDeposit(String clientEventId) {
        if (clientEventId == null) return;
        boolean removed = false;
        for (Iterator<Op> it = ops.iterator(); it.hasNext(); ) {
            Op op = it.next();
            if (op.isDeposit() && clientEventId.equals(op.clientEventId)) {
                it.remove();
                removed = true;
            }
        }
        if (removed) save();
    }

    /** Clears a withdraw once the items have actually been handed over. */
    public synchronized void clearWithdraw(long seq) {
        boolean removed = false;
        for (Iterator<Op> it = ops.iterator(); it.hasNext(); ) {
            Op op = it.next();
            if (op.isWithdraw() && op.seq == seq) {
                it.remove();
                removed = true;
            }
        }
        if (removed) save();
    }

    /** Everything still outstanding. */
    public synchronized List<Op> all() {
        return new ArrayList<>(ops);
    }

    public synchronized boolean isEmpty() {
        return ops.isEmpty();
    }

    public synchronized int size() {
        return ops.size();
    }

    /** Drops everything — used when the log is reset and no record here still applies. */
    public synchronized void clear() {
        if (ops.isEmpty()) return;
        ops.clear();
        save();
    }

    private void save() {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, gson.toJson(ops).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // If this write fails the operation is unprotected, which is exactly the
            // state everything was in before. Say so rather than failing the trade.
            System.err.println("[economiesmod] could not save pending ops: " + e);
        }
    }
}
