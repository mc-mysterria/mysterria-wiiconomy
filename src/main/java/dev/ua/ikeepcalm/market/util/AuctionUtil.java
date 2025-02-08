/*
 * Decompiled with CFR 0.150.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 *  org.bukkit.inventory.ItemStack
 *  org.bukkit.inventory.meta.ItemMeta
 */
package dev.ua.ikeepcalm.market.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.ua.ikeepcalm.market.auction.inventories.MyItemsInventoryManager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.DecimalFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class AuctionUtil {
    private static final Logger LOGGER = LoggerFactory.getLogger(AuctionUtil.class);
    private static final DecimalFormat decimalFormat = new DecimalFormat("#.0");
    private HikariDataSource dataSource;
    private Path dataFolder;
    private String tableName = "auctions";
    private Executor dbExecutor;
    private Cache<UUID, AuctionData> itemDataCache;

    public AuctionUtil(Path dataFolder) throws IOException {
        if (!Files.exists(dataFolder)) {
            Files.createDirectories(dataFolder);
        }
        this.dataFolder = dataFolder;
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + dataFolder.resolve("auctions.db"));
        config.setPoolName("AuctionPool");
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(1);
        config.setIdleTimeout(30000L);
        config.setConnectionTimeout(5000L);
        this.dataSource = new HikariDataSource(config);
        this.itemDataCache = Caffeine.newBuilder().expireAfterWrite(24L, TimeUnit.HOURS).maximumSize(1000L).build();
        this.dbExecutor = Executors.newFixedThreadPool(10);
        this.createTableIfNotExists();
        this.loadData();
    }

    private void createTableIfNotExists() {
        try (Connection connection = this.dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("CREATE TABLE IF NOT EXISTS " + this.tableName + " (UUID TEXT PRIMARY KEY,Buyer TEXT,TimeStamp LONG,Price INTEGER,Seller TEXT,Item TEXT);")){
            statement.executeUpdate();
        }
        catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void loadData() {
        CompletableFuture.runAsync(() -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime twentyFourHoursAgo = now.minusHours(24L);
            long timestampTwentyFourHoursAgo = twentyFourHoursAgo.toEpochSecond(ZoneOffset.UTC);
            String query = "SELECT * FROM " + this.tableName + " WHERE TimeStamp > " + timestampTwentyFourHoursAgo + ";";
            try (Connection connection = this.dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(query);
                 ResultSet resultSet = statement.executeQuery()){
                while (resultSet.next()) {
                    UUID id = UUID.fromString(resultSet.getString("UUID"));
                    long timeStamp = resultSet.getLong("TimeStamp");
                    int days = (int)((now.toEpochSecond(ZoneOffset.UTC) - timeStamp) / 86400L);
                    String buyer = resultSet.getString("Buyer");
                    if (!(days > 14 && buyer != null || days > 1 && buyer == null)) {
                        int price = resultSet.getInt("Price");
                        String seller = resultSet.getString("Seller");
                        String itemString = resultSet.getString("Item");
                        ItemStack itemStack = BukkitSerialization.itemStackArrayFromBase64(itemString)[0];
                        AuctionData data = new AuctionData(id, buyer, timeStamp, price, seller, itemStack);
                        this.itemDataCache.put(id, data);
                        continue;
                    }
                    this.removeAuctionData(id);
                }
            }
            catch (SQLException e) {
                e.printStackTrace();
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }, this.dbExecutor);
    }

    public void addBuyer(UUID uuid, String buyerName) {
        this.itemDataCache.getIfPresent(uuid).setBuyer(buyerName);
        CompletableFuture.runAsync(() -> {
            String updateQuery = "UPDATE " + this.tableName + " SET Buyer = ? WHERE UUID = ?;";
            try (Connection connection = this.dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(updateQuery)){
                statement.setString(1, buyerName);
                statement.setString(2, uuid.toString());
                statement.executeUpdate();
                AuctionData auctionData = this.itemDataCache.getIfPresent(uuid);
                if (auctionData != null) {
                    auctionData.setBuyer(buyerName);
                }
            }
            catch (SQLException var12) {
                LOGGER.error("Failed to add buyer to auction data", var12);
            }
        }, this.dbExecutor);
    }

    public Map<UUID, AuctionData> getPlayerTransactions(String playerName) {
        HashMap<UUID, AuctionData> playerTransactions = new HashMap<>();
        for (Map.Entry<UUID, AuctionData> entry : this.itemDataCache.asMap().entrySet()) {
            AuctionData data = entry.getValue();
            if ((data.getBuyer() == null || !playerName.equals(data.getBuyer())) && (data.getSeller() == null || !playerName.equals(data.getSeller()))) continue;
            playerTransactions.put(entry.getKey(), data);
        }
        return playerTransactions;
    }

    public CompletableFuture<Map<UUID, AuctionData>> getPlayerSellingItems(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            HashMap<UUID, AuctionData> playerSellingItems = new HashMap<>();
            for (Map.Entry<UUID, AuctionData> entry : this.itemDataCache.asMap().entrySet()) {
                AuctionData data = entry.getValue();
                if (!playerName.equals(data.getSeller()) || data.getBuyer() != null) continue;
                playerSellingItems.put(entry.getKey(), data);
            }
            return playerSellingItems;
        }, this.dbExecutor);
    }

    public String[] getTotalSpentAndMade(Map<UUID, AuctionData> playerTransactions, String playerName) {
        int totalSpent = 0;
        int totalMade = 0;
        for (AuctionData data : playerTransactions.values()) {
            if (data.getBuyer() == null || data.getSeller() == null) continue;
            if (playerName.equals(data.getBuyer())) {
                totalSpent += data.getPrice();
            }
            if (!playerName.equals(data.getSeller())) continue;
            totalMade += data.getPrice();
        }
        String formattedTotalSpent = getFormattedPrice(totalSpent);
        String formattedTotalMade = getFormattedPrice(totalMade);
        return new String[]{formattedTotalSpent, formattedTotalMade};
    }

    public CompletableFuture<Map<UUID, AuctionData>> getExpiredItemsByPlayerName(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            LocalDateTime now = LocalDateTime.now();
            LocalDateTime twentyFourHoursAgo = now.minusHours(24L);
            long timestampTwentyFourHoursAgo = twentyFourHoursAgo.toEpochSecond(ZoneOffset.UTC);
            HashMap<UUID, AuctionData> expiredItems = new HashMap<>();
            for (Map.Entry<UUID, AuctionData> entry : this.itemDataCache.asMap().entrySet()) {
                AuctionData data = entry.getValue();
                if (!playerName.equals(data.getSeller()) || data.getTimeStamp() > timestampTwentyFourHoursAgo || data.getBuyer() != null) continue;
                expiredItems.put(entry.getKey(), data);
            }
            return expiredItems;
        }, this.dbExecutor);
    }

    public void close() {
        if (this.dataSource != null) {
            this.dataSource.close();
        }
    }

    public CompletableFuture<Map<UUID, AuctionData>> getFilteredAuctionItems(String filter) {
        return CompletableFuture.supplyAsync(() -> {
            HashMap<UUID, AuctionData> filteredItems = new HashMap<>();
            for (Map.Entry<UUID, AuctionData> data : this.itemDataCache.asMap().entrySet()) {
                ItemStack item = data.getValue().getItem();
                ItemMeta itemMeta = item.getItemMeta();
                if (itemMeta == null) continue;
                boolean matchesLore = itemMeta.hasLore() && Objects.requireNonNull(itemMeta.getLore()).stream().anyMatch(lore -> lore.contains(filter));
                boolean matchesDisplayName = itemMeta.hasDisplayName() && itemMeta.getDisplayName().contains(filter);
                boolean matchesMaterial = item.getType().name().contains(filter.toUpperCase());
                if (!matchesLore && !matchesDisplayName && !matchesMaterial) continue;
                filteredItems.put(data.getKey(), data.getValue());
            }
            return filteredItems;
        }, this.dbExecutor);
    }

    public CompletableFuture<Void> saveAuctionData(AuctionData data) {
        return CompletableFuture.runAsync(() -> {
            String serializedItem = BukkitSerialization.itemStackArrayToBase64(new ItemStack[]{data.getItem()});
            String insertQuery = "INSERT INTO " + this.tableName + " (UUID, Buyer, TimeStamp, Price, Seller, Item) VALUES (?, ?, ?, ?, ?, ?);";
            try (Connection connection = this.dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(insertQuery)){
                statement.setString(1, data.getId().toString());
                statement.setString(2, data.getBuyer());
                statement.setLong(3, data.getTimeStamp());
                statement.setInt(4, data.getPrice());
                statement.setString(5, data.getSeller());
                statement.setString(6, serializedItem);
                statement.executeUpdate();
                this.itemDataCache.put(data.getId(), data);
            }
            catch (SQLException e) {
                LOGGER.error("Failed to save auction data", e);
            }
        }, this.dbExecutor);
    }

    public void removeAuctionData(UUID uuid) {
        CompletableFuture.runAsync(() -> {
            String deleteQuery = "DELETE FROM " + this.tableName + " WHERE UUID = ?;";
            try (Connection connection = this.dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(deleteQuery)){
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
                this.itemDataCache.invalidate(uuid);
            }
            catch (SQLException e) {
                LOGGER.error("Failed to remove auction data", e);
            }
        }, this.dbExecutor);
    }

    public void removeAuctionData(UUID uuid, MyItemsInventoryManager manager, Player player) {
        CompletableFuture.runAsync(() -> {
            String deleteQuery = "DELETE FROM " + this.tableName + " WHERE UUID = ?;";
            try (Connection connection = this.dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(deleteQuery)){
                statement.setString(1, uuid.toString());
                statement.executeUpdate();
                this.itemDataCache.invalidate(uuid);
            }
            catch (SQLException e) {
                LOGGER.error("Failed to remove auction data", e);
            }
            manager.open(player);
        }, this.dbExecutor);
    }

    public static String getFormattedPrice(int cost) {
        int verlDors = cost / (64 * 64);
        cost %= 64 * 64;
        int licks = cost / 64;
        cost %= 64;
        String result = "";
        if (cost > 0) {
            result = cost + " коп";
        }
        if (licks > 0) {
            result = licks + " лік " + result;
        }
        if (verlDors > 0) {
            result = verlDors + " аур " + result;
        }
        result = result.strip();
        if (result.isEmpty()) {
            return "0 коп";
        }
        return result;
    }

    public HikariDataSource getDataSource() {
        return this.dataSource;
    }

    public Path getDataFolder() {
        return this.dataFolder;
    }

    public String getTableName() {
        return this.tableName;
    }

    public Executor getDbExecutor() {
        return this.dbExecutor;
    }

    public Cache<UUID, AuctionData> getItemDataCache() {
        return this.itemDataCache;
    }

    public void setDataSource(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void setDataFolder(Path dataFolder) {
        this.dataFolder = dataFolder;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public void setDbExecutor(Executor dbExecutor) {
        this.dbExecutor = dbExecutor;
    }

    public void setItemDataCache(Cache<UUID, AuctionData> itemDataCache) {
        this.itemDataCache = itemDataCache;
    }
}
