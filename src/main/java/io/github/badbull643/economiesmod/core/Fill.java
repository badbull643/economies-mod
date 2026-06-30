package io.github.badbull643.economiesmod.core;



//is this the class for filling an order?
//yes nevermind
public class Fill {
    private final long buyerId;
    private final long sellerId;
    private final long quantity;
    private final long price;
    private final int itemId;

    public Fill(long buyerId, long sellerId, long quantity, long price, int itemId) {
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.quantity = quantity;
        this.price = price;
        this.itemId = itemId;
    }
    //fill out these in a second
    public long buyerId()  { return buyerId; }
    public long sellerId() { return sellerId; }
    public long quantity() { return quantity; }
    public long price()    { return price; }
    public int itemId()    { return itemId; }
    public long amount()   { return quantity * price; }
}
