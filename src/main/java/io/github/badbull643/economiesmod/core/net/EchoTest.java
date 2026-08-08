package io.github.badbull643.economiesmod.core.net;

import java.net.ServerSocket;
import java.net.Socket;

public class EchoTest {

    public static void main(String[] args) throws Exception {
        // Start a server on a background thread
        Thread server = new Thread(() -> {
            try (ServerSocket ss = new ServerSocket(25555)) {
                System.out.println("[server] listening on 25555");
                try (Socket s = ss.accept();
                     MessageChannel ch = new MessageChannel(s)) {
                    System.out.println("[server] client connected from " + ch.remoteAddress());

                    Message msg = ch.receive();
                    if (msg instanceof Message.Hello) {
                        Message.Hello h = (Message.Hello) msg;
                        System.out.println("[server] got Hello from " + h.userId
                                + " at seq " + h.lastSeq);

                        Message.Sync sync = new Message.Sync();
                        sync.logLines = java.util.Collections.emptyList();
                        sync.complete = true;
                        ch.send(sync);
                        System.out.println("[server] sent empty Sync");
                    } else {
                        System.out.println("[server] unexpected first message: " + msg.type);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        server.start();

        Thread.sleep(300);   // let the server bind

        // Client
        try (Socket s = new Socket("localhost", 25555);
             MessageChannel ch = new MessageChannel(s)) {
            Message.Hello hello = new Message.Hello();
            hello.userId = "00000000-0000-0000-0000-000000000001";
            hello.lastSeq = 0;
            hello.lastHash = "0";
            hello.protocolVersion = "1";
            ch.send(hello);
            System.out.println("[client] sent Hello");

            Message reply = ch.receive();
            System.out.println("[client] got " + reply.type);
            if (reply instanceof Message.Sync) {
                Message.Sync sync = (Message.Sync) reply;
                System.out.println("[client] sync had " + sync.logLines.size()
                        + " events, complete=" + sync.complete);
            }
        }

        server.join(2000);
        System.out.println("done");
    }
}