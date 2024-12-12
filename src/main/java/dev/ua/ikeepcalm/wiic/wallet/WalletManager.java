package dev.ua.ikeepcalm.wiic.wallet;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.wallet.objects.WalletData;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
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

    public void createWallet(UUID id, String name) {
        if (!isDuplicate(id)) {
            WalletData wallet = new WalletData(id, name);
            saveWallet(wallet);
        } else {
            throw new IllegalArgumentException("Wallet already exists");
        }
    }

    public void updateWallet(WalletData wallet) {
        if (wallet.getId() != null) {
            File walletFile = new File(walletDirectory, wallet.getId().toString() + ".json");
            if (walletFile.exists()) {
                saveWallet(wallet);
            } else {
                throw new IllegalArgumentException("Wallet not found");
            }
        } else {
            throw new IllegalArgumentException("Wallet ID cannot be null");
        }
    }

    private void saveWallet(WalletData wallet) {
        File walletFile = new File(walletDirectory, wallet.getId().toString() + ".json");
        try (FileWriter writer = new FileWriter(walletFile)) {
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            gson.toJson(wallet, writer);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
