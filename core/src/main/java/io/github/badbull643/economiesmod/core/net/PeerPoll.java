package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.GameVersions;
import io.github.badbull643.economiesmod.core.PeerCache;

import java.util.*;
import java.util.concurrent.*;


/** Probes every known peer concurrently and reports who's hosting. */
public class PeerPoll {

    public static class HostInfo {
        public final PeerCache.Peer peer;
        public final Message.QueryReply reply;

        HostInfo(PeerCache.Peer peer, Message.QueryReply reply) {
            this.peer = peer;
            this.reply = reply;
        }

        /**
         * Whether this host serves the Minecraft version the caller is on. A host on another
         * one would refuse the caller at the door, so it has no business on a list of hosts
         * to join — and a host that says nothing is a 1.16.5 host, as it always has been.
         */
        public boolean servesVersion(String gameVersion) {
            return GameVersions.same(reply.gameVersion, gameVersion);
        }
    }

    /**
     * Probes all peers in parallel. Returns whoever answered within the deadline.
     * Blocking — call off the game thread.
     */
    public static List<HostInfo> findHosts(List<PeerCache.Peer> peers,
                                           PeerCache cache, int timeoutMillis) {
        if (peers.isEmpty()) return Collections.emptyList();

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(peers.size(), 16));
        List<Future<HostInfo>> futures = new ArrayList<>();

        for (PeerCache.Peer p : peers) {
            futures.add(pool.submit(() -> {
                Probe.Result r = Probe.probe(p.address, p.port, timeoutMillis);
                if (!r.reachable || !r.reply.hosting) return null;

                // Pin or verify the host's key against what we've seen before.
                if (cache != null && cache.keyChanged(r.reply.userId, r.reply.publicKey)) {
                    System.err.println("[peers] " + r.reply.hostName
                            + " presented a changed key — proceeding anyway");
                }
                return new HostInfo(p, r.reply);
            }));
        }
        pool.shutdown();

        Map<String, HostInfo> byHost = new LinkedHashMap<>();
        try {
            pool.awaitTermination(timeoutMillis + 500L, TimeUnit.MILLISECONDS);
            for (Future<HostInfo> f : futures) {
                if (f.isDone()) {
                    HostInfo h = f.get();
                    if (h != null && h.reply.userId != null) {
                        byHost.putIfAbsent(h.reply.userId, h);   // collapse duplicates
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[peers] poll interrupted: " + e);
        } finally {
            pool.shutdownNow();
        }
        return new ArrayList<>(byHost.values());
    }
}