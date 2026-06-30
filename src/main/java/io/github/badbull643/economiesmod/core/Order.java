package io.github.badbull643.economiesmod.core;

//will define a transfer function later

public class Order {
    //so just use isbid? oppo is false anyway,
    private boolean isbid_;
    private long volume_;
    //minecraft item ids,
    private int itemID_;
    private long value_;
    private long UserID_;

    //add logic for expceptions when things like price/ volume not > 0
    //also add the item safeguard
    public Order(long iprice, int iitemID, long ivolume, boolean iisBid, long UID) {
        this.isbid_ = iisBid;
        this.volume_ = ivolume;
        this.itemID_ = iitemID;
        this.value_ = iprice;
        this.UserID_ = UID;
    }

    //helper functions
    public boolean isBid() {
        return isbid_;
    }

    public long volume() {
        return volume_;
    }

    public int itemID() {
        return itemID_;
    }

    public long userID() {
        return UserID_;
    }

    public long value() {
        return value_;
    }

    public void reduceVolume(long qty) {
        volume_ -= qty;
    }
    //add a helpfunction for total cost of an order
}