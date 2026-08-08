package io.github.badbull643.economiesmod.client;

import io.github.badbull643.economiesmod.core.*;

import java.io.IOException;
import java.nio.file.Path;

public class MarketStateHolder {
    private static MarketState state;
    private static EventLog log;

    public static MarketState get() {
        if (state == null) state = new MarketState();
        return state;
    }

    public static void load(Path worldDir) {
        Path logFile = worldDir.resolve("economiesmod").resolve("market.jsonl");
        try {
            log = new EventLog(logFile);
            long bad = log.verifyChain();
            if (bad != -1) {
                System.err.println("[economiesmod] log chain broken at seq " + bad);
            }
            state = EventApplier.replay(log);
            System.out.println("[economiesmod] replayed " + log.lastSeq() + " events");
        } catch (IOException e) {
            System.err.println("[economiesmod] log load failed: " + e);
            state = new MarketState();
        }
    }

    /** Logs an event then applies it. The only way state changes. */
    public static EventApplier.Result submit(Event event) {
        if (log == null) return EventApplier.Result.reject("no log open");
        try {
            SequencedEvent se = log.append(event);
            return EventApplier.apply(get(), se);
        } catch (IOException e) {
            return EventApplier.Result.reject("failed to write log: " + e.getMessage());
        }
    }
}
