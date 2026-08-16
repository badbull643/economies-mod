package io.github.badbull643.economiesmod.core.net;

import com.google.gson.Gson;
import io.github.badbull643.economiesmod.core.Event;

import java.net.Socket;
import java.util.UUID;

public class TestClient {

    public static void main(String[] args) throws Exception {
        String userId = args.length > 0 ? args[0]
                : "00000000-0000-0000-0000-000000000001";
        String label = args.length > 1 ? args[1] : "A";

        try (Socket s = new Socket("localhost", 25555);
             MessageChannel ch = new MessageChannel(s)) {

            Message.Hello hello = new Message.Hello();
            hello.userId = userId;
            hello.lastSeq = 0;
            hello.lastHash = "0";
            hello.protocolVersion = "1";
            ch.send(hello);

            Message reply = ch.receive();
            if (reply instanceof Message.Sync) {
                System.out.println("[" + label + "] synced "
                        + ((Message.Sync) reply).logLines.size() + " events");
            } else if (reply instanceof Message.Error) {
                System.out.println("[" + label + "] ERROR: " + ((Message.Error) reply).reason);
                return;
            }

            // Propose one event. Unsigned, so the host will reject it — this harness
            // exists to exercise framing and sync, not the proposal path.
            Event.Deposit d = new Event.Deposit();
            d.userId = UUID.fromString(userId);
            d.itemId = "minecraft:iron_ingot";
            d.quantity = 1;
            d.timestamp = System.currentTimeMillis();

            Message.Propose p = new Message.Propose();
            p.clientEventId = UUID.randomUUID().toString();
            p.eventType = "Deposit";
            p.eventJson = new Gson().toJson(d);
            ch.send(p);
            System.out.println("[" + label + "] proposed Deposit");

            // Stay connected and print everything that arrives
            System.out.println("[" + label + "] listening...");
            Message m;
            while ((m = ch.receive()) != null) {
                if (m instanceof Message.Accepted) {
                    String line = ((Message.Accepted) m).logLine;
                    // Just show the seq so it's readable
                    System.out.println("[" + label + "] broadcast seq="
                            + line.substring(line.indexOf("\"seq\":") + 6,
                            line.indexOf(",", line.indexOf("\"seq\":"))));
                } else {
                    System.out.println("[" + label + "] " + m.type);
                }
            }
        }
    }
}