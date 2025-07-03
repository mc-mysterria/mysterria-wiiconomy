package dev.ua.ikeepcalm.wiic.currency.models;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class WalletData {

    private int verlDors;
    private int licks;
    private int coppets;

    public WalletData(int totalCoppets) {
        setTotalCoppets(totalCoppets);
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
