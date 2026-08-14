package dev.ua.ikeepcalm.wiic.domain.agora.plots.service;

import dev.ua.ikeepcalm.wiic.WIIC;
import dev.ua.ikeepcalm.wiic.domain.agora.plots.model.PlotRegion;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Captures and replays the pristine block state of a plot cuboid, so an evicted
 * plot goes back to exactly the stall the builders made.
 *
 * <p>Format: gzipped {@code [version][palette of BlockData strings][one palette
 * index per block in x→z→y order]}. A palette keeps repeated air/stone cheap and
 * the gzip layer flattens the index run — a 16³ stall lands in a couple of KB.
 * Block data strings are the vanilla text form, so they survive server upgrades the
 * same way {@code ItemStack.serializeAsBytes} does.
 *
 * <p>{@link #capture} and every block write in {@link #restore} are main-thread
 * only. Restores are paced at {@link #BLOCKS_PER_TICK} blocks per tick — a plot is
 * never worth a server freeze.
 */
public class PlotSnapshot {

    private static final int FORMAT_VERSION = 1;
    private static final int BLOCKS_PER_TICK = 500;

    private PlotSnapshot() {}

    /** Reads every block of {@code region}. Main thread; returns the blob to persist. */
    public static byte[] capture(World world, PlotRegion region) throws IOException {
        Map<String, Integer> palette = new HashMap<>();
        List<String> ordered = new ArrayList<>();
        int[] indices = new int[region.volume()];

        int i = 0;
        for (int y = region.minY(); y <= region.maxY(); y++) {
            for (int x = region.minX(); x <= region.maxX(); x++) {
                for (int z = region.minZ(); z <= region.maxZ(); z++) {
                    String data = world.getBlockAt(x, y, z).getBlockData().getAsString();
                    Integer existing = palette.get(data);
                    if (existing == null) {
                        existing = ordered.size();
                        palette.put(data, existing);
                        ordered.add(data);
                    }
                    indices[i++] = existing;
                }
            }
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(ordered.size());
            for (String data : ordered) out.writeUTF(data);
            out.writeInt(indices.length);
            for (int index : indices) out.writeInt(index);
        }
        return bytes.toByteArray();
    }

    /**
     * Replays {@code blob} over {@code region}, {@value #BLOCKS_PER_TICK} blocks per
     * tick, then runs {@code onDone} on the main thread. Blocks are written without
     * physics so restored stalls don't collapse mid-replay.
     *
     * @throws IOException if the blob is corrupt or was captured for a different cuboid.
     */
    public static void restore(WIIC plugin, World world, PlotRegion region, byte[] blob,
                               Runnable onDone) throws IOException {
        List<String> palette = new ArrayList<>();
        int[] indices;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(blob)))) {
            int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IOException("unsupported plot snapshot version " + version);
            }
            int paletteSize = in.readInt();
            for (int p = 0; p < paletteSize; p++) palette.add(in.readUTF());
            indices = new int[in.readInt()];
            for (int i = 0; i < indices.length; i++) indices[i] = in.readInt();
        }
        if (indices.length != region.volume()) {
            throw new IOException("plot snapshot covers " + indices.length
                    + " blocks but the region is now " + region.volume() + " — redefine the plot");
        }

        // Pre-resolve the palette once; createBlockData is the expensive part.
        BlockData[] resolved = new BlockData[palette.size()];
        for (int p = 0; p < resolved.length; p++) resolved[p] = Bukkit.createBlockData(palette.get(p));

        int width = region.maxX() - region.minX() + 1;
        int depth = region.maxZ() - region.minZ() + 1;
        int layer = width * depth;
        int[] cursor = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            // A plugin disable cancels this timer outright, so the last thing each pass does
            // is check whether it is the final one — and the caller's continuation runs on a
            // cancelled-early path too, or an interrupted restore would strand the plot in
            // PlotService's `evicting` set until the next restart.
            if (!plugin.isEnabled()) {
                task.cancel();
                onDone.run();
                return;
            }
            int budget = 0;
            while (cursor[0] < indices.length && budget < BLOCKS_PER_TICK) {
                int i = cursor[0]++;
                int y = region.minY() + i / layer;
                int rest = i % layer;
                int x = region.minX() + rest / depth;
                int z = region.minZ() + rest % depth;
                Block block = world.getBlockAt(x, y, z);
                BlockData target = resolved[indices[i]];
                if (!block.getBlockData().equals(target)) {
                    block.setBlockData(target, false);
                }
                budget++;
            }
            if (cursor[0] >= indices.length) {
                task.cancel();
                onDone.run();
            }
        }, 1L, 1L);
    }
}
