package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.BotRules;
import io.github.badbull643.economiesmod.core.MarketStats;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The commands a dedicated server's operator types into its console.
 *
 *   stats
 *   cancel-orders <player> [reason]
 *   ban <player> [reason]
 *   bot spawn <producer|maker> [count] [tick|threads]
 *   bot list
 *   bot pause <bot>
 *   bot resume <bot>
 *   help
 *
 * A bot is named by its id, the first few characters of it, or like producer#2. A player by
 * their id, the start of it, or the name they last connected under.
 *
 * Reads standard input on a thread of its own. A server started with nothing attached to its
 * input (a service, a container without a tty) reads end-of-file at once, and this thread
 * just ends — there is nothing else for it to do there.
 */
public final class BotConsole {
    private BotConsole() {}

    private static final String HELP =
            "server commands:\n"
            + "  stats                                             the money supply, who holds it, and what goods exist\n"
            + "  cancel-orders <player> [reason]                   take all of a player's orders off the book;\n"
            + "                                 what they reserved goes back to them (needs this\n"
            + "                                 server to have created the market)\n"
            + "  ban <player> [reason]                             deny them in the config, disconnect them, and\n"
            + "                                 cancel their orders\n"
            + "  a <player> is their id, the start of it, or the name they connected with\n"
            + "\n"
            + "bot commands:\n"
            + "  bot spawn <producer|maker> [count] [tick|threads] [item]\n"
            + "                                 add bots (count defaults to 1, scheduler to tick;\n"
            + "                                 a producer makes the item named, or the one\n"
            + "                                 fewest producers make)\n"
            + "  bot list                                          every bot and what it holds\n"
            + "  bot pause <bot>                                   take its orders off the book and stop it\n"
            + "  bot resume <bot>                                  start it again\n"
            + "  a <bot> is its id, the start of its id, or a name like producer#2";

    public static void start(HostServer server) {
        Thread t = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;
                    try {
                        handle(server, line, BotConsole::say);
                    } catch (Exception e) {
                        say("that did not work: " + e.getMessage());
                    }
                }
            } catch (Exception ignored) {
                // Input went away. The server carries on without a console.
            }
        }, "bot-console");
        t.setDaemon(true);
        t.start();
    }

    private static void say(String s) {
        for (String line : s.split("\n")) System.out.println("[bots] " + line);
    }

    /** One command. Package-private so a test can type into it without a console. */
    static void handle(HostServer server, String line, Consumer<String> reply) {
        String[] w = line.trim().split("\\s+");
        String first = w[0].toLowerCase(Locale.ROOT);
        if (first.equals("help") || first.equals("?")) {
            reply.accept(HELP);
            return;
        }
        if (first.equals("stats")) {
            reply.accept(MarketStats.of(server.state()).describe(server::nameOf, 5, 10));
            return;
        }
        if (first.equals("cancel-orders") || first.equals("ban")) {
            if (w.length < 2) {
                reply.accept("whose? " + first + " <player> [reason]");
                return;
            }
            UUID player = server.resolvePlayer(w[1]);
            BotRunner bots = server.botRunner();
            if (player == null && bots != null && bots.resolve(w[1]) != null) {
                reply.accept("'" + w[1] + "' is a bot — pause it instead (bot pause " + w[1]
                        + "), which also takes its orders off the book");
                return;
            }
            if (player == null) {
                reply.accept("no single player matches '" + w[1] + "' — use their id, or the"
                        + " start of it, if the name is shared or they never connected here");
                return;
            }
            String reason = w.length > 2 ? String.join(" ", Arrays.copyOfRange(w, 2, w.length)) : null;
            if (first.equals("ban")) {
                server.banPlayer(player, reason, reply);
            } else {
                server.cancelOrdersOf(player,
                        reason != null ? reason : "cancelled by the server's operator", reply);
            }
            return;
        }
        if (!first.equals("bot") && !first.equals("bots")) {
            reply.accept("unknown command '" + w[0] + "' — try: help");
            return;
        }
        String verb = w.length > 1 ? w[1].toLowerCase(Locale.ROOT) : "help";
        if (verb.equals("help")) {
            reply.accept(HELP);
            return;
        }

        BotRunner runner = server.botRunner();
        if (runner == null) {
            reply.accept(server.state().botsEnabled()
                    ? "the bots are not running yet — wait for the server to finish starting"
                    : "this market has no bots. They are switched on when a market is created,"
                            + " by starting the server with --bots on an empty log");
            return;
        }

        switch (verb) {
            case "spawn": {
                if (w.length < 3) {
                    reply.accept("which type? bot spawn <producer|maker> [count] [tick|threads]");
                    return;
                }
                String type = typeOf(w[2]);
                if (type == null) {
                    reply.accept("no bot type '" + w[2] + "' — there are: " + String.join(", ", BotRules.TYPES));
                    return;
                }
                int count = 1;
                BotRunner.Mode mode = BotRunner.Mode.TICK;
                String product = null;
                for (int i = 3; i < w.length; i++) {
                    BotRunner.Mode m = BotRunner.Mode.parse(w[i]);
                    if (m != null) {
                        mode = m;
                        continue;
                    }
                    String item = w[i].contains(":") ? w[i] : "minecraft:" + w[i];
                    if (BotRules.ITEMS.contains(item)) {
                        if (!BotRules.PRODUCER.equals(type)) {
                            reply.accept("only producers make an item of their own");
                            return;
                        }
                        product = item;
                        continue;
                    }
                    try {
                        count = Integer.parseInt(w[i]);
                    } catch (NumberFormatException e) {
                        reply.accept("expected a count, tick|threads, or one of the basket items ("
                                + String.join(", ", BotRules.ITEMS).replace("minecraft:", "") + "), not '" + w[i] + "'");
                        return;
                    }
                }
                if (count < 1 || count > BotRules.MAX_PER_TYPE) {
                    reply.accept("count must be between 1 and " + BotRules.MAX_PER_TYPE);
                    return;
                }
                runner.spawn(type, count, mode, product, reply);
                return;
            }
            case "list": {
                List<String> lines = runner.describe();
                if (lines.isEmpty()) reply.accept("no bots yet (bot spawn <producer|maker> [count])");
                else reply.accept(String.join("\n", lines));
                return;
            }
            case "pause":
            case "resume": {
                if (w.length < 3) {
                    reply.accept("which bot? bot " + verb + " <id or name like producer#2>");
                    return;
                }
                UUID id = runner.resolve(w[2]);
                if (id == null) {
                    reply.accept("no single bot matches '" + w[2] + "' (bot list shows them)");
                    return;
                }
                if (verb.equals("pause")) runner.pause(id, reply);
                else runner.resume(id, reply);
                return;
            }
            default:
                reply.accept("unknown bot command '" + w[1] + "' (try: bot help)");
        }
    }

    /** What an operator typed for a type, as the type: producers, market-maker, mm and so on. */
    static String typeOf(String typed) {
        String t = typed.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
        if (t.endsWith("s")) t = t.substring(0, t.length() - 1);
        switch (t) {
            case "producer": return BotRules.PRODUCER;
            case "maker": case "marketmaker": case "mm": return BotRules.MARKET_MAKER;
            default: return null;
        }
    }
}
