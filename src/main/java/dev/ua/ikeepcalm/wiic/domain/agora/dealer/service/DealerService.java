package dev.ua.ikeepcalm.wiic.domain.agora.dealer.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.config.MarketConfig;
import dev.ua.ikeepcalm.wiic.domain.agora.db.MarketDatabase;
import dev.ua.ikeepcalm.wiic.domain.agora.db.StashDao;
import dev.ua.ikeepcalm.wiic.domain.agora.db.TransactionDao;
import dev.ua.ikeepcalm.wiic.domain.agora.dealer.model.DealerItem;
import dev.ua.ikeepcalm.wiic.domain.agora.dealer.model.source.DealResult;
import dev.ua.ikeepcalm.wiic.domain.agora.ledger.model.StashItem;
import dev.ua.ikeepcalm.wiic.domain.agora.utils.journal.MarketJournal;
import dev.ua.ikeepcalm.wiic.utils.FileUtil;
import dev.ua.ikeepcalm.wiic.utils.ItemUtil;
import dev.ua.ikeepcalm.wiic.utils.TransactionLogger;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

public class DealerService {

    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();
    private static final long CHECK_TICKS = 20L * 60;

    private final WIIC plugin;
    private final MarketConfig config;
    private final MarketDatabase db;
    private final MarketJournal journal;
    private final Path file;
    private final Map<String, DealerItem> items = new LinkedHashMap<>();
    private final Map<String, Integer> offers = new LinkedHashMap<>();
    private final Map<UUID, Integer> purchases = new HashMap<>();
    private long rolledAt;
    @Getter
    private long nextRollAt;
    private String day = "";
    private BukkitTask task;
    private boolean loaded;

    public DealerService(WIIC plugin, MarketConfig config, MarketDatabase db, MarketJournal journal) {
        this.plugin = plugin;
        this.config = config;
        this.db = db;
        this.journal = journal;
        this.file = plugin.getDataFolder().toPath().resolve("dealer.yml");
    }

    public void start() throws IOException, InvalidConfigurationException {
        load();
        rollIfDue();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::rollIfDue, CHECK_TICKS, CHECK_TICKS);
    }

    public void stop() {
        if (task != null) task.cancel();
    }

    public Collection<DealerItem> pool() {
        return Collections.unmodifiableCollection(items.values());
    }

    public Map<DealerItem, Integer> offers() {
        Map<DealerItem, Integer> current = new LinkedHashMap<>();
        offers.forEach((id, remaining) -> {
            DealerItem item = items.get(id);
            if (item != null) current.put(item, remaining);
        });
        return current;
    }

    public int purchasesLeft(UUID player) {
        int limit = config.dealerPlayerDailyLimit();
        return limit == 0 ? Integer.MAX_VALUE : Math.max(0, limit - today().getOrDefault(player, 0));
    }

    public String add(ItemStack item, long price, int dailyStock, int weight) {
        int next = items.keySet().stream().mapToInt(Integer::parseInt).max().orElse(0) + 1;
        String id = String.valueOf(next);
        items.put(id, new DealerItem(id, item.serializeAsBytes(), price, dailyStock, weight));
        save();
        return id;
    }

    public boolean remove(String id) {
        if (items.remove(id) == null) return false;
        offers.remove(id);
        save();
        return true;
    }

    public void roll() {
        List<DealerItem> candidates = new ArrayList<>(items.values().stream().filter(item -> item.getWeight() > 0).toList());
        ThreadLocalRandom random = ThreadLocalRandom.current();
        offers.clear();
        while (offers.size() < config.dealerOffersPerRotation() && !candidates.isEmpty()) {
            long ticket = random.nextLong(candidates.stream().mapToLong(DealerItem::getWeight).sum());
            for (Iterator<DealerItem> it = candidates.iterator(); it.hasNext(); ) {
                DealerItem candidate = it.next();
                ticket -= candidate.getWeight();
                if (ticket < 0) {
                    offers.put(candidate.getId(), candidate.getDailyStock());
                    it.remove();
                    break;
                }
            }
        }
        rolledAt = System.currentTimeMillis();
        nextRollAt = rolledAt + config.dealerRotationMs();
        save();
        plugin.getLogger().info("Curio dealer restocked with offers " + offers.keySet());
    }

    private void rollIfDue() {
        if (System.currentTimeMillis() >= nextRollAt || (offers.isEmpty() && !items.isEmpty())) roll();
    }

    public void purchase(Player buyer, String itemId, Consumer<DealResult> callback) {
        UUID uuid = buyer.getUniqueId();
        if (!config.dealerEnabled()) {
            callback.accept(DealResult.CLOSED);
            return;
        }
        if (!IN_FLIGHT.add(uuid)) {
            callback.accept(DealResult.BUSY);
            return;
        }
        DealResult refusal = reserve(uuid, itemId);
        if (refusal != null) {
            finish(uuid, callback, refusal);
            return;
        }
        DealerItem item = items.get(itemId);
        long rotation = rolledAt;
        String attemptId = UUID.randomUUID().toString();
        try {
            journal.append(MarketJournal.Type.DEALER_BUY, attemptId, uuid, item.getPrice(), item.getItemBytes(), itemId);
        } catch (IllegalStateException e) {
            plugin.getLogger().severe("Market journal unavailable, refusing dealer sale: " + e.getMessage());
            release(rotation, itemId, uuid);
            finish(uuid, callback, DealResult.ERROR);
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!VaultUtil.withdraw(uuid, item.getPrice())) {
                journal.remove(attemptId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    release(rotation, itemId, uuid);
                    finish(uuid, callback, DealResult.INSUFFICIENT_FUNDS);
                });
                return;
            }
            try {
                journal.append(MarketJournal.Type.DEALER_PAID, attemptId, uuid, item.getPrice(), null, itemId);
            } catch (IllegalStateException e) {
                plugin.getLogger().severe("Market journal marker write failed after dealer withdraw, refunding: " + e.getMessage());
                boolean refunded = VaultUtil.deposit(uuid, item.getPrice());
                TransactionLogger.logNote(buyer, "MARKET DEALER refund of " + item.getPrice() + " coppets (journal write failed) "
                        + (refunded ? "OK" : "FAILED"));
                if (!refunded) plugin.getLogger().severe("Failed to refund " + item.getPrice() + " coppets to " + uuid);
                journal.remove(attemptId);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    release(rotation, itemId, uuid);
                    finish(uuid, callback, DealResult.ERROR);
                });
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> deliver(buyer, item, attemptId, callback));
        });
    }

    private @Nullable DealResult reserve(UUID uuid, String itemId) {
        DealerItem item = items.get(itemId);
        Integer remaining = offers.get(itemId);
        if (item == null || remaining == null || remaining <= 0) return DealResult.SOLD_OUT;
        if (purchasesLeft(uuid) <= 0) return DealResult.LIMIT_REACHED;
        int bought = today().getOrDefault(uuid, 0);
        offers.put(itemId, remaining - 1);
        purchases.put(uuid, bought + 1);
        if (save()) return null;
        offers.put(itemId, remaining);
        purchases.put(uuid, bought);
        return DealResult.ERROR;
    }

    private void release(long rotation, String itemId, UUID uuid) {
        if (rotation == rolledAt) offers.computeIfPresent(itemId, (id, remaining) -> remaining + 1);
        today().computeIfPresent(uuid, (id, bought) -> Math.max(0, bought - 1));
        save();
    }

    private void deliver(Player buyer, DealerItem item, String attemptId, Consumer<DealResult> callback) {
        UUID uuid = buyer.getUniqueId();
        ItemStack goods = item.item();
        TransactionLogger.logNote(buyer, "MARKET DEALER bought offer " + item.getId() + " " + goods.getType()
                + " x" + goods.getAmount() + " for " + item.getPrice() + " coppets");
        db.submit(conn -> {
            TransactionDao.log(conn, "DEALER_BUY", uuid, null, null, item.getPrice(), "offer " + item.getId());
            return null;
        });
        // The journal entry must be gone before the item exists in an inventory, or recovery would stash a second copy.
        if (buyer.isOnline() && journal.remove(attemptId)) {
            ItemUtil.giveOrDrop(buyer, goods);
            finish(uuid, callback, DealResult.DELIVERED);
            return;
        }
        db.transactionThenMain(conn -> {
            StashDao.insert(conn, new StashItem(UUID.fromString(attemptId), uuid, item.getItemBytes(),
                    goods.getType(), goods.getAmount(), null, StashItem.SOURCE_DEALER, item.getId(),
                    System.currentTimeMillis()));
            return null;
        }, done -> {
            journal.remove(attemptId);
            finish(uuid, callback, DealResult.STASHED);
        }, error -> {
            plugin.getLogger().severe("Dealer goods for " + uuid + " could not be stashed (" + error
                    + "); journal entry " + attemptId + " will stash them at the next start");
            finish(uuid, callback, DealResult.STASHED);
        });
    }

    private void finish(UUID uuid, Consumer<DealResult> callback, DealResult result) {
        IN_FLIGHT.remove(uuid);
        callback.accept(result);
    }

    private Map<UUID, Integer> today() {
        String now = LocalDate.now().toString();
        if (!now.equals(day)) {
            day = now;
            purchases.clear();
        }
        return purchases;
    }

    private void load() throws IOException, InvalidConfigurationException {
        if (!Files.exists(file)) {
            loaded = true;
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.load(file.toFile());
        ConfigurationSection pool = yaml.getConfigurationSection("items");
        if (pool != null) {
            for (String id : pool.getKeys(false)) {
                ConfigurationSection entry = pool.getConfigurationSection(id);
                items.put(id, new DealerItem(id, Base64.getDecoder().decode(entry.getString("item")),
                        entry.getLong("price"), entry.getInt("daily-stock"), entry.getInt("weight")));
            }
        }
        rolledAt = yaml.getLong("rotation.rolled-at");
        nextRollAt = yaml.getLong("rotation.next-at");
        ConfigurationSection current = yaml.getConfigurationSection("rotation.offers");
        if (current != null) current.getKeys(false).forEach(id -> offers.put(id, current.getInt(id)));
        day = yaml.getString("rotation.day", "");
        ConfigurationSection bought = yaml.getConfigurationSection("rotation.purchases");
        if (bought != null) bought.getKeys(false).forEach(id -> purchases.put(UUID.fromString(id), bought.getInt(id)));
        loaded = true;
    }

    private boolean save() {
        if (!loaded) return false;
        YamlConfiguration yaml = new YamlConfiguration();
        for (DealerItem item : items.values()) {
            String path = "items." + item.getId() + ".";
            yaml.set(path + "item", Base64.getEncoder().encodeToString(item.getItemBytes()));
            yaml.set(path + "price", item.getPrice());
            yaml.set(path + "daily-stock", item.getDailyStock());
            yaml.set(path + "weight", item.getWeight());
        }
        yaml.set("rotation.rolled-at", rolledAt);
        yaml.set("rotation.next-at", nextRollAt);
        offers.forEach((id, remaining) -> yaml.set("rotation.offers." + id, remaining));
        yaml.set("rotation.day", day);
        purchases.forEach((uuid, bought) -> yaml.set("rotation.purchases." + uuid, bought));
        try {
            FileUtil.writeAtomically(file, yaml.saveToString());
            return true;
        } catch (IOException e) {
            plugin.getLogger().severe("Failed to save dealer.yml: " + e.getMessage());
            return false;
        }
    }

    public static void releaseAll() {
        IN_FLIGHT.clear();
    }
}
