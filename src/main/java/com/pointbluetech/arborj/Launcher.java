package com.pointbluetech.arborj;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Non-JavaFX launcher that bypasses the module system check.
 * Also reads ~/.arborj/jvm.options for user-configured heap size
 * and re-launches with the correct -Xmx if needed.
 */
public class Launcher {

    private static final Path JVM_OPTIONS_FILE =
            Path.of(System.getProperty("user.home"), ".arborj", "jvm.options");

    public static void main(String[] args) {
        // Enable LCD subpixel font rendering on Windows (fixes thin/unclear fonts)
        System.setProperty("prism.lcdtext", "true");
        System.setProperty("prism.text", "t2k");

        // Suppress JavaFX "unsupported configuration" warning when running from classpath
        System.setProperty("javafx.verbose", "false");

        // Initialize file logging before anything else
        com.pointbluetech.arborj.service.AppLogger.init();

        // Check if we need to re-launch with a different heap size
        String requestedHeap = readHeapSetting();
        if (requestedHeap != null && !isCurrentHeap(requestedHeap)) {
            try {
                relaunchWithHeap(requestedHeap, args);
                return; // Parent process exits; child takes over
            } catch (Exception e) {
                System.err.println("[ArborJ] Failed to relaunch with " + requestedHeap + ": " + e.getMessage());
                // Fall through to normal launch
            }
        }

        ArborJApp.main(args);
    }

    private static String readHeapSetting() {
        try {
            if (Files.exists(JVM_OPTIONS_FILE)) {
                String content = Files.readString(JVM_OPTIONS_FILE).trim();
                if (content.startsWith("-Xmx")) return content;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean isCurrentHeap(String xmx) {
        // Check if current JVM was already launched with this heap
        for (String arg : java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            if (arg.startsWith("-Xmx")) {
                return arg.equalsIgnoreCase(xmx);
            }
        }
        return false;
    }

    private static void relaunchWithHeap(String xmx, String[] appArgs) throws Exception {
        String javaHome = System.getProperty("java.home");
        String javaBin = Path.of(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        var cmd = new java.util.ArrayList<String>();
        cmd.add(javaBin);
        cmd.add(xmx);
        cmd.add("--enable-native-access=ALL-UNNAMED");
        cmd.add("-cp");
        cmd.add(classpath);
        cmd.add(Launcher.class.getName());
        cmd.addAll(java.util.List.of(appArgs));

        System.out.println("[ArborJ] Relaunching with " + xmx);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.inheritIO();
        Process process = pb.start();
        System.exit(process.waitFor());
    }
}
