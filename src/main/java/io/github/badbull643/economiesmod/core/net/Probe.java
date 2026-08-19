package io.github.badbull643.economiesmod.core.net;

import io.github.badbull643.economiesmod.core.PlayerKeys;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.PublicKey;
import java.util.UUID;


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
        String nonce = UUID.randomUUID().toString();
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);

            try (MessageChannel ch = new MessageChannel(socket)) {
                Message.Query q = new Message.Query();
                q.protocolVersion = HostServer.PROTOCOL_VERSION;
                q.nonce = nonce;
                ch.send(q);

                Message reply = ch.receive();
                if (!(reply instanceof Message.QueryReply)) {
                    return new Result(false, null);
                }
                Message.QueryReply qr = (Message.QueryReply) reply;

                if (!verify(qr, nonce)) {
                    System.err.println("[probe] " + host + ":" + port
                            + " sent an unverifiable reply — ignoring");
                    return new Result(false, null);
                }
                return new Result(true, qr);
            }
        } catch (Exception e) {
            return new Result(false, null);
        }
    }

    private static boolean verify(Message.QueryReply r, String expectedNonce) {
        if (r.signature == null || r.publicKey == null || r.userId == null) return false;
        try {
            PublicKey key = PlayerKeys.decodePublic(r.publicKey);
            return PlayerKeys.verify(queryPayload(r, expectedNonce), r.signature, key);
        } catch (Exception e) {
            return false;
        }
    }

    public static String queryPayload(Message.QueryReply r, String nonce) {
        return nonce + "|" + r.userId + "|" + r.hostName + "|"
                + r.lastSeq + "|" + r.lastHash + "|" + r.clientCount
                + "|" + r.marketId + "|" + r.marketName
                + "|" + r.dedicated;
    }
}