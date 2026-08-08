package io.github.badbull643.economiesmod.core;
import java.util.UUID;


//is this the class for filling an order?
//yes nevermind
public class Fill {
    private final UUID buyerId;
    private final UUID sellerId;
    private final long quantity;
    private final long price;
    private final String itemId;      // was int

    public Fill(UUID buyerId, UUID sellerId, long quantity, long price, String itemId) {
        this.buyerId = buyerId;
        this.sellerId = sellerId;
        this.quantity = quantity;
        this.price = price;
        this.itemId = itemId;
    }

    public UUID buyerId()  { return buyerId; }
    public UUID sellerId() { return sellerId; }
    public long quantity() { return quantity; }
    public long price()    { return price; }
    public String itemId() { return itemId; }      // was int
    public long amount()   { return Math.multiplyExact(quantity, price); }
}
