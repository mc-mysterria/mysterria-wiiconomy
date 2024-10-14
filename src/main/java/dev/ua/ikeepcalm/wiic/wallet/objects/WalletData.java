package dev.ua.ikeepcalm.wiic.wallet.objects;

import java.util.UUID;

public class WalletData {


    private int verlDors;
    private int licks;
    private int coppets;
    private UUID id;
    private String owner;

    public WalletData(UUID id, String owner) {
        this.id = id;
        this.verlDors = 0;
        this.licks = 0;
        this.coppets = 0;
        this.owner = owner;
    }

    public int getVerlDors() {
        return verlDors;
    }

    public void setVerlDors(int verlDors) {
        this.verlDors = verlDors;
    }

    public int getLicks() {
        return licks;
    }

    public void setLicks(int licks) {
        this.licks = licks;
    }

    public int getCoppets() {
        return coppets;
    }

    public void setCoppets(int coppets) {
        this.coppets = coppets;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String owner) {
        this.owner = owner;
    }
}
