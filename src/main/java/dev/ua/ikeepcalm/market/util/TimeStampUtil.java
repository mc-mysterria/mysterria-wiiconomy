/*
 * Decompiled with CFR 0.150.
 */
package dev.ua.ikeepcalm.market.util;

import java.text.SimpleDateFormat;
import java.util.Date;

public class TimeStampUtil {
    public static String getCountdown(long timestamp, long secondsBeforeExpiration) {
        long expirationTime = timestamp + secondsBeforeExpiration * 1000L;
        long countdown = expirationTime - System.currentTimeMillis();
        if (countdown <= 0L) {
            return "00:00:00";
        }
        long hours = countdown / 3600000L % 24L;
        long minutes = countdown / 60000L % 60L;
        long seconds = countdown / 1000L % 60L;
        return String.format("%02dг %02dх %02dс", hours, minutes, seconds);
    }

    public static String formatTimestamp(long timestamp) {
        Date date = new Date(timestamp);
        SimpleDateFormat formatter = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss");
        return formatter.format(date);
    }
}

