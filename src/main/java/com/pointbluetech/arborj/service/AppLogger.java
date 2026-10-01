package com.pointbluetech.arborj.service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple file logger that writes to ~/.arborj/arborj.log.
 * Rolls over when the log exceeds MAX_SIZE_MB, keeping one backup.
 * Also redirects System.out and System.err to the log file.
 */
public class AppLogger {

    private static final Path LOG_DIR = Path.of(System.getProperty("user.home"), ".arborj");
    private static final Path LOG_FILE = LOG_DIR.resolve("arborj.log");
    private static final Path BACKUP_FILE = LOG_DIR.resolve("arborj.log.1");
    private static final long MAX_SIZE_BYTES = 5 * 1024 * 1024; // 5 MB
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private static PrintStream logStream;
    private static PrintStream originalOut;
    private static PrintStream originalErr;

    /**
     * Initialize logging. Call once at app startup.
     * Redirects System.out and System.err to the log file while
     * also echoing to the original console (for dev/debug).
     */
    public static void init() {
        try {
            Files.createDirectories(LOG_DIR);
            rolloverIfNeeded();

            FileOutputStream fos = new FileOutputStream(LOG_FILE.toFile(), true);
            logStream = new PrintStream(fos, true);

            originalOut = System.out;
            originalErr = System.err;

            // Tee to both file and console
            System.setOut(new TeePrintStream(originalOut, logStream, "INFO"));
            System.setErr(new TeePrintStream(originalErr, logStream, "ERROR"));

            System.out.println("=== ArborJ started ===");
        } catch (Exception e) {
            System.err.println("Failed to initialize logging: " + e.getMessage());
        }
    }

    private static void rolloverIfNeeded() {
        try {
            if (Files.exists(LOG_FILE) && Files.size(LOG_FILE) > MAX_SIZE_BYTES) {
                // Delete old backup, rename current to backup
                Files.deleteIfExists(BACKUP_FILE);
                Files.move(LOG_FILE, BACKUP_FILE);
            }
        } catch (IOException e) {
            // Ignore — will append to existing
        }
    }

    /**
     * PrintStream that writes to both the original stream and a log file,
     * prepending timestamps to each line.
     */
    private static class TeePrintStream extends PrintStream {
        private final PrintStream original;
        private final PrintStream logFile;
        private final String level;

        TeePrintStream(PrintStream original, PrintStream logFile, String level) {
            super(original, true);
            this.original = original;
            this.logFile = logFile;
            this.level = level;
        }

        @Override
        public void println(String s) {
            String ts = LocalDateTime.now().format(TS_FMT);
            String line = ts + " [" + level + "] " + s;
            original.println(s); // Console gets original message
            logFile.println(line); // File gets timestamped
            logFile.flush();
        }

        @Override
        public void println(Object x) {
            println(String.valueOf(x));
        }

        @Override
        public void print(String s) {
            original.print(s);
            logFile.print(s);
            logFile.flush();
        }

        @Override
        public PrintStream printf(String format, Object... args) {
            String s = String.format(format, args);
            original.print(s);
            logFile.print(s);
            logFile.flush();
            return this;
        }
    }
}
