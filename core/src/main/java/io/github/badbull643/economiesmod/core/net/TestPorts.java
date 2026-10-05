package io.github.badbull643.economiesmod.core.net;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A port a test can hand to a HostServer and expect it to bind.
 *
 * <h2>Why not new ServerSocket(0)</h2>
 *
 * Every suite had its own copy of this:
 *
 * <pre>
 *     try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
 * </pre>
 *
 * which opens a port, <b>closes it</b>, and hands the number to something else to bind.
 * Between those two steps the port belongs to nobody, and port 0 asks the operating
 * system for one from the <em>ephemeral</em> range — the same range it draws from for
 * outgoing connections. These suites make a great many outgoing connections. So the
 * number handed over could be taken, by this process, before the server bound it.
 *
 * It failed the nine-suite run twice on 2026-08-22, both times with every check passing
 * and the build red — the shape of failure that teaches people to ignore CI, which is
 * why this is being fixed before the workflow that would hit it. See backlog item 4.
 *
 * <h2>What this does instead</h2>
 *
 * Draws from a fixed range well below the ephemeral one, so nothing else on the machine
 * is being handed these numbers while a test holds one. Remembers what it has given out,
 * so two suites in one JVM cannot collide however unlucky the draw. And probes the
 * candidate the same way HostServer will bind it — {@code new ServerSocket(port)}, the
 * wildcard address, not loopback — because a probe that binds more narrowly than the
 * server proves nothing.
 *
 * The window between the probe closing and the server binding still exists; it cannot be
 * closed without handing the socket itself over, which would mean changing HostServer to
 * suit its tests. What has changed is that nothing else is competing for the number.
 */
final class TestPorts {

    private TestPorts() {}

    /**
     * Above the registered range, below the ephemeral one.
     *
     * Windows draws ephemeral ports from 49152 upwards and Linux from 32768, so this
     * sits under both. Ten thousand of them against the handful a run needs, which is
     * what makes a repeat unlikely enough that the memory below is a belt to the braces.
     */
    private static final int FIRST = 20_000;
    private static final int LAST = 30_000;

    private static final Random RANDOM = new Random();
    private static final Set<Integer> handedOut = ConcurrentHashMap.newKeySet();

    /** A port nothing is listening on, never returned twice in this JVM. */
    static int free() throws IOException {
        for (int attempt = 0; attempt < 100; attempt++) {
            int port = FIRST + RANDOM.nextInt(LAST - FIRST);
            if (!handedOut.add(port)) continue;

            // Bound and released exactly as the server will bind it. A candidate that
            // fails here is in use by something outside this JVM, which is the one case
            // the memory above cannot see.
            try (ServerSocket probe = new ServerSocket(port)) {
                probe.getLocalPort();
            } catch (IOException inUse) {
                continue;
            }
            return port;
        }
        throw new IOException("no free port between " + FIRST + " and " + LAST);
    }
}
