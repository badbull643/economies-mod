package io.github.badbull643.economiesmod.core;
import java.util.UUID;

//will define a transfer function later

public class Order {
    private final long orderId_;
    private boolean isbid_;
    private long volume_;
    private String itemID_;      // was int
    private long value_;
    private UUID userID_;        // was long

    public Order(long orderId, long iprice, String iitemID, long ivolume,
                 boolean iisBid, UUID UID) {
        this.orderId_ = orderId;
        this.isbid_ = iisBid;
        this.volume_ = ivolume;
        this.itemID_ = iitemID;
        this.value_ = iprice;
        this.userID_ = UID;
    }

    public long orderId() { return orderId_; }
    public boolean isBid() { return isbid_; }
    public long volume() { return volume_; }
    public String itemID() { return itemID_; }
    public UUID userID() { return userID_; }
    public long value() { return value_; }
    public void reduceVolume(long qty) { volume_ -= qty; }
}