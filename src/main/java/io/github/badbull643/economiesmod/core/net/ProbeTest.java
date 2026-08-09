package io.github.badbull643.economiesmod.core.net;


public class ProbeTest {
    public static void main(String[] args) {
        long start = System.currentTimeMillis();
        Probe.Result live = Probe.probe("localhost", 25555, 2000);
        System.out.println("live probe: reachable=" + live.reachable
                + " (" + (System.currentTimeMillis() - start) + "ms)");
        if (live.reachable) {
            System.out.println("  hosting=" + live.reply.hosting
                    + " seq=" + live.reply.lastSeq
                    + " host=" + live.reply.hostName
                    + " clients=" + live.reply.clientCount);
        }

        start = System.currentTimeMillis();
        Probe.Result dead = Probe.probe("localhost", 25599, 2000);
        System.out.println("dead probe: reachable=" + dead.reachable
                + " (" + (System.currentTimeMillis() - start) + "ms)");
    }
}
