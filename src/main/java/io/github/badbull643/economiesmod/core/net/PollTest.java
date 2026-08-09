package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.PeerCache;

import java.nio.file.Paths;
import java.util.List;

public class PollTest {
    public static void main(String[] args) {
        PeerCache cache = new PeerCache(
                Paths.get("run/config/economiesmod-peers-Alice.json"));

        long start = System.currentTimeMillis();
        List<PeerPoll.HostInfo> hosts = PeerPoll.findHosts(cache.all(), 2000);
        long elapsed = System.currentTimeMillis() - start;

        System.out.println("polled " + cache.all().size() + " peers in " + elapsed + "ms");
        for (PeerPoll.HostInfo h : hosts) {
            System.out.println("  hosting: " + h.reply.hostName
                    + " at " + h.peer.address + ":" + h.peer.port
                    + " seq=" + h.reply.lastSeq
                    + " clients=" + h.reply.clientCount);
        }
        if (hosts.isEmpty()) System.out.println("  (nobody hosting)");
    }
}