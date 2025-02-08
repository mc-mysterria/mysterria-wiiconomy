package dev.ua.ikeepcalm.wiic.wallet.objects;

import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Setter
@Getter
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

    public int getTotalCoppets() {
        return getVerlDors() * 64 * 64 + getLicks() * 64 + getCoppets();
    }

    public void setTotalCoppets(int totalCoppets) {
        setVerlDors(totalCoppets / (64 * 64));
        totalCoppets %= 64 * 64;
        setLicks(totalCoppets / 64);
        setCoppets(totalCoppets % 64);
    }
}
