package dev.ua.ikeepcalm.wiic.domain.money.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.utils.VaultUtil;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class MoneyFlowService implements Listener {

    private static final String UPDATE_EVENT = "io.github.townyadvanced.iconomy.events.AccountUpdateEvent";
    private static final Set<String> TRANSPORT_PLUGINS = Set.of("iConomyUnlocked", "Vault", "VaultUnlocked");
    private static final String UNATTRIBUTED = "server";

    private final WIIC plugin;
    private final Path folder;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(r -> new Thread(r, "WIIC-MoneyFlow"));
    private final Map<Class<?>, String> sourceByClass = new ConcurrentHashMap<>();
    private final StackWalker walker = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);
    private MethodHandle accountUuid;
    private MethodHandle accountName;
    private MethodHandle previous;
    private MethodHandle balance;

    public MoneyFlowService(WIIC plugin) {
        this.plugin = plugin;
        this.folder = plugin.getDataFolder().toPath().resolve("money-flow");
    }

    public boolean start() {
        Plugin economy = plugin.getServer().getPluginManager().getPlugin("iConomyUnlocked");
        if (economy == null) return false;
        try {
            Class<? extends Event> eventClass = Class.forName(UPDATE_EVENT, true, economy.getClass().getClassLoader()).asSubclass(Event.class);
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            accountUuid = lookup.findVirtual(eventClass, "getAccountUUID", MethodType.methodType(UUID.class));
            accountName = lookup.findVirtual(eventClass, "getAccountName", MethodType.methodType(String.class));
            previous = lookup.findVirtual(eventClass, "getPrevious", MethodType.methodType(double.class));
            balance = lookup.findVirtual(eventClass, "getBalance", MethodType.methodType(double.class));
            Files.createDirectories(folder);
            plugin.getServer().getPluginManager().registerEvent(eventClass, this, EventPriority.MONITOR,
                    (listener, event) -> {
                        if (eventClass.isInstance(event)) record(event);
                    }, plugin);
            return true;
        } catch (ReflectiveOperationException | IOException e) {
            plugin.getLogger().warning("Money flow log disabled, iConomyUnlocked API not as expected: " + e);
            return false;
        }
    }

    public void stop() {
        writer.shutdown();
        try {
            writer.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void record(Event event) {
        try {
            double before = (double) previous.invoke(event);
            double after = (double) balance.invoke(event);
            if (before == after) return;
            UUID uuid = (UUID) accountUuid.invoke(event);
            String account = uuid != null ? uuid.toString() : ((String) accountName.invoke(event)).replace(',', '_');
            String line = System.currentTimeMillis() + "," + account + "," + (after - before) + "," + after + "," + resolveSource() + System.lineSeparator();
            LocalDate day = LocalDate.now(ZoneId.systemDefault());
            writer.execute(() -> append(day, line));
        } catch (Throwable e) {
            plugin.getLogger().warning("Could not record money flow: " + e);
        }
    }

    private String resolveSource() {
        return walker.walk(frames -> frames
                .map(StackWalker.StackFrame::getDeclaringClass)
                .filter(type -> type != MoneyFlowService.class && type != VaultUtil.class)
                .map(type -> sourceByClass.computeIfAbsent(type, this::sourceOf))
                .filter(source -> !source.isEmpty())
                .findFirst()
                .orElse(UNATTRIBUTED));
    }

    private String sourceOf(Class<?> type) {
        try {
            String owner = JavaPlugin.getProvidingPlugin(type).getName();
            if (TRANSPORT_PLUGINS.contains(owner)) return "";
            String name = type.getName();
            String simple = name.substring(name.lastIndexOf('.') + 1);
            int nested = simple.indexOf('$');
            return owner + "/" + (nested > 0 ? simple.substring(0, nested) : simple);
        } catch (IllegalArgumentException | IllegalStateException notPluginClass) {
            return "";
        }
    }

    private void append(LocalDate day, String line) {
        try {
            Files.writeString(folder.resolve(day + ".csv"), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("Could not write money flow: " + e.getMessage());
        }
    }

    public CompletableFuture<List<String>> summarize(int days) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, double[]> bySource = new TreeMap<>();
            LocalDate today = LocalDate.now(ZoneId.systemDefault());
            for (int i = 0; i < days; i++) {
                Path file = folder.resolve(today.minusDays(i) + ".csv");
                if (!Files.exists(file)) continue;
                try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
                    lines.forEach(row -> tally(bySource, row));
                } catch (IOException e) {
                    plugin.getLogger().warning("Could not read " + file.getFileName() + ": " + e.getMessage());
                }
            }
            return format(bySource, days);
        }, writer);
    }

    private static void tally(Map<String, double[]> bySource, String row) {
        String[] parts = row.split(",", 5);
        if (parts.length < 5) return;
        double delta;
        try {
            delta = Double.parseDouble(parts[2]);
        } catch (NumberFormatException malformed) {
            return;
        }
        double[] totals = bySource.computeIfAbsent(parts[4], key -> new double[3]);
        totals[delta > 0 ? 0 : 1] += Math.abs(delta);
        totals[2]++;
    }

    private static List<String> format(Map<String, double[]> bySource, int days) {
        List<String> out = new ArrayList<>();
        double in = 0;
        double outflow = 0;
        out.add("Money flow, last " + days + " day(s), in coppets:");
        List<Map.Entry<String, double[]>> sorted = new ArrayList<>(bySource.entrySet());
        sorted.sort((a, b) -> Double.compare(b.getValue()[0] + b.getValue()[1], a.getValue()[0] + a.getValue()[1]));
        for (Map.Entry<String, double[]> entry : sorted) {
            double[] totals = entry.getValue();
            in += totals[0];
            outflow += totals[1];
            out.add(String.format("%s: +%.0f / -%.0f (net %+.0f, %d tx)",
                    entry.getKey(), totals[0], totals[1], totals[0] - totals[1], (long) totals[2]));
        }
        out.add(String.format("Total: +%.0f / -%.0f (net %+.0f)", in, outflow, in - outflow));
        return out;
    }
}
