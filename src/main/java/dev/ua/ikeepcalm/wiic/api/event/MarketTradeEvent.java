package dev.ua.ikeepcalm.wiic.api.event;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

import java.util.UUID;

/**
 * Fired on the main thread after a WIIC market trade has completed: the buyer has paid and
 * the goods have been delivered or placed in the buyer's stash. An attempted purchase, a
 * failed withdraw, a refund and a listing that was only reserved do not fire this event.
 *
 * <p>This is a read-only notification; it cannot be cancelled. The seller is {@code null}
 * when WIIC itself was the seller, as with the curio dealer. The material is the plugin's
 * item identity for the traded stack and the bytes are the serialized {@code ItemStack} the
 * plugin stores, so listeners that need NBT can deserialize them. Amount and total price
 * describe the whole trade, not one unit.
 */
public class MarketTradeEvent extends Event {

    /** The currency of every WIIC market price and trade: whole coppets. */
    public static final String CURRENCY = "coppets";

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID buyer;
    private final UUID seller;
    private final Material material;
    private final byte[] itemBytes;
    private final int amount;
    private final double totalPrice;
    private final String currency;

    public MarketTradeEvent(UUID buyer, UUID seller, Material material, byte[] itemBytes,
                            int amount, double totalPrice, String currency) {
        this.buyer = buyer;
        this.seller = seller;
        this.material = material;
        this.itemBytes = itemBytes == null ? null : itemBytes.clone();
        this.amount = amount;
        this.totalPrice = totalPrice;
        this.currency = currency;
    }

    /** The unique id of the player who bought the goods. */
    public UUID getBuyerUuid() {
        return buyer;
    }

    /** The unique id of the seller, or {@code null} when WIIC sold the goods itself. */
    public UUID getSellerUuid() {
        return seller;
    }

    /** The material of the goods that changed hands. */
    public Material getMaterial() {
        return material;
    }

    /** A copy of WIIC's serialized {@code ItemStack} for the goods, or {@code null} if unavailable. */
    public byte[] getItemBytes() {
        return itemBytes == null ? null : itemBytes.clone();
    }

    /** How many items were traded. */
    public int getAmount() {
        return amount;
    }

    /** The total the buyer paid for the whole trade. */
    public double getTotalPrice() {
        return totalPrice;
    }

    /** The currency of {@link #getTotalPrice()}, currently always {@link #CURRENCY}. */
    public String getCurrency() {
        return currency;
    }

    /**
     * Fires a completed trade through Bukkit. Must be called on the main thread.
     * Listener failures are handled by Bukkit as usual and cannot undo the trade.
     *
     * @param seller {@code null} when WIIC was the seller
     */
    public static void fire(UUID buyer, UUID seller, Material material, byte[] itemBytes,
                            int amount, long totalPrice) {
        Bukkit.getPluginManager().callEvent(
                new MarketTradeEvent(buyer, seller, material, itemBytes, amount, totalPrice, CURRENCY));
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
