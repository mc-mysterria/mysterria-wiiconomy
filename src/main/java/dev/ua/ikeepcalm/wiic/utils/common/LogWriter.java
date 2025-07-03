package dev.ua.ikeepcalm.wiic.utils.common;

import java.io.*;
import java.util.Calendar;

public class LogWriter {
    private final FileWriter fileWriter;
    private final BufferedWriter bufferedWriter;
    private final PrintWriter printWriter;

    public LogWriter(File file) {
        try {
            this.fileWriter = new FileWriter(file, true);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.bufferedWriter = new BufferedWriter(fileWriter);
        this.printWriter = new PrintWriter(bufferedWriter);
    }

    public void write(String line) {
        printWriter.println("[" + Calendar.getInstance().getTime() + "] " + line);
    }

    public void close() throws IOException {
        printWriter.close();
        bufferedWriter.close();
        fileWriter.close();
    }
}
