/*
 * Decompiled with CFR 0.150.
 * 
 * Could not load the following classes:
 *  org.bukkit.inventory.ItemStack
 */
package dev.ua.ikeepcalm.market.util;

import dev.ua.ikeepcalm.wiic.WIIC;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

public class AuctionData {
    private UUID id;
    private String buyer;
    private long timeStamp;
    private int price;
    private String seller;
    private final ItemStack item;

    public String getFormattedPrice() {
        return AuctionUtil.getFormattedPrice(this.price);
    }

    public UUID getId() {
        return this.id;
    }

    public String getBuyer() {
        return this.buyer;
    }

    public long getTimeStamp() {
        return this.timeStamp;
    }

    public int getPrice() {
        return this.price;
    }

    public String getSeller() {
        return this.seller;
    }

    public ItemStack getItem() {
        return this.item;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public void setBuyer(String buyer) {
        this.buyer = buyer;
    }

    public void setTimeStamp(long timeStamp) {
        this.timeStamp = timeStamp;
    }

    public void setPrice(int price) {
        this.price = price;
    }

    public void setSeller(String seller) {
        this.seller = seller;
    }

    public AuctionData(UUID id, String buyer, long timeStamp, int price, String seller, ItemStack item) {
        this.id = id;
        this.buyer = buyer;
        this.timeStamp = timeStamp;
        this.price = price;
        this.seller = seller;
        this.item = item;
    }
}

