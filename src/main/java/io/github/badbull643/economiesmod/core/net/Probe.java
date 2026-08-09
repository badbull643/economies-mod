package io.github.badbull643.economiesmod.core.net;

import java.net.InetSocketAddress;
import java.net.Socket;

public class Probe {

    public static class Result {
        public final boolean reachable;
        public final Message.QueryReply reply;

        Result(boolean reachable, Message.QueryReply reply) {
            this.reachable = reachable;
            this.reply = reply;
        }
    }

    /** Probes an address. Never throws — unreachable is a result, not an error. */
    public static Result probe(String host, int port, int timeoutMillis) {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            try (MessageChannel ch = new MessageChannel(socket)) {
                Message.Query q = new Message.Query();
                q.protocolVersion = HostServer.PROTOCOL_VERSION;
                ch.send(q);

                Message reply = ch.receive();
                if (reply instanceof Message.QueryReply) {
                    return new Result(true, (Message.QueryReply) reply);
                }
                return new Result(false, null);
            }
        } catch (Exception e) {
            return new Result(false, null);
        }
    }
}