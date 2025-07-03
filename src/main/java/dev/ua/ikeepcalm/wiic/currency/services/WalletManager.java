package dev.ua.ikeepcalm.wiic.currency.services;

import com.google.gson.Gson;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.currency.models.WalletData;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.UUID;

public class WalletManager {

    private final File walletDirectory = new File(WIIC.INSTANCE.getDataFolder(), "wallets");

    public WalletManager() {
        if (!walletDirectory.exists()) {
            walletDirectory.mkdirs();
        }
    }

    public WalletData getWallet(String id) {
        File walletFile = new File(walletDirectory, id + ".json");
        if (walletFile.exists()) {
            try (FileReader reader = new FileReader(walletFile)) {
                Gson gson = new Gson();
                return gson.fromJson(reader, WalletData.class);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public boolean isDuplicate(UUID id) {
        File walletFile = new File(walletDirectory, id.toString() + ".json");
        return walletFile.exists();
    }

}
