package dev.ua.ikeepcalm.wiic.utils;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Bukkit-independent audit projection of an item serialized with
 * {@code ItemStack#serializeAsBytes()} (gzip-compressed NBT). Pure byte parsing, so it is safe
 * on any thread, unlike {@code ItemStack.deserializeBytes}. Reads only the item id, count and
 * the {@code circleofimagination} identity keys under
 * {@code components."minecraft:custom_data".PublicBukkitValues}.
 */
final class SerializedItemProjection {
    private static final int MAX_DEPTH = 64;
    private static final String CUSTOM_DATA = "minecraft:custom_data";
    private static final String BUKKIT_VALUES = "PublicBukkitValues";

    private SerializedItemProjection() {
    }

    /** Result keys: {@code material}, {@code item_amount}, and the raw PDC strings under their PDC key. */
    static Map<String, Object> read(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(bytes)))) {
            if (in.readByte() != 10) throw new IOException("root is not a compound");
            in.readUTF();
            Map<String, Object> out = new LinkedHashMap<>();
            readCompound(in, out, "", 0);
            return out;
        }
    }

    private static void readCompound(DataInputStream in, Map<String, Object> out, String path, int depth)
            throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("nbt too deep");
        while (true) {
            byte type = in.readByte();
            if (type == 0) return;
            String name = in.readUTF();
            String child = path.isEmpty() ? name : path + "/" + name;
            if (type == 10) {
                readCompound(in, out, child, depth + 1);
            } else if (type == 8) {
                String value = in.readUTF();
                if (path.isEmpty() && name.equals("id")) {
                    int colon = value.indexOf(':');
                    out.put("material", (colon >= 0 ? value.substring(colon + 1) : value).toLowerCase(Locale.ROOT));
                } else if (path.equals("components/" + CUSTOM_DATA + "/" + BUKKIT_VALUES)) {
                    out.put(name, value);
                }
            } else if (path.isEmpty() && name.equals("count") && type >= 1 && type <= 3) {
                out.put("item_amount", type == 1 ? in.readByte() : type == 2 ? in.readShort() : in.readInt());
            } else {
                skip(in, type, depth + 1);
            }
        }
    }

    private static void skip(DataInputStream in, byte type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("nbt too deep");
        switch (type) {
            case 1 -> skipFully(in, 1);
            case 2 -> skipFully(in, 2);
            case 3, 5 -> skipFully(in, 4);
            case 4, 6 -> skipFully(in, 8);
            case 7 -> skipFully(in, length(in));
            case 8 -> skipFully(in, in.readUnsignedShort());
            case 9 -> {
                byte element = in.readByte();
                int size = length(in);
                for (int i = 0; i < size; i++) skip(in, element, depth + 1);
            }
            case 10 -> {
                while (true) {
                    byte child = in.readByte();
                    if (child == 0) break;
                    skipFully(in, in.readUnsignedShort());
                    skip(in, child, depth + 1);
                }
            }
            case 11 -> skipFully(in, (long) length(in) * 4);
            case 12 -> skipFully(in, (long) length(in) * 8);
            default -> throw new IOException("unknown nbt tag " + type);
        }
    }

    private static int length(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0) throw new IOException("negative nbt length");
        return length;
    }

    private static void skipFully(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long skipped = in.skip(n);
            if (skipped <= 0) {
                if (in.read() < 0) throw new EOFException();
                skipped = 1;
            }
            n -= skipped;
        }
    }
}
