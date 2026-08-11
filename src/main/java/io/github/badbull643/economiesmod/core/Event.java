package io.github.badbull643.economiesmod.core;

import java.util.UUID;

public abstract class Event {
    public UUID userId;
    public String clientEventId;
    public long timestamp;

    public static class Deposit extends Event {
        public String itemId;
        public long quantity;
    }

    public static class Withdraw extends Event {
        public String itemId;
        public long quantity;
    }

    public static class PlaceOrder extends Event {
        public String itemId;
        public long price;
        public long volume;
        public boolean isBid;
    }

    public static class CancelOrder extends Event {
        public long orderId;
        public String itemId;
        public boolean isBid;
    }

    public static class InjectCredits extends Event {
        public UUID targetUserId;
        public long amount;
    }

    public static class DepositAndList extends Event {
        public String itemId;
        public long quantity;
        public long price;
    }

}