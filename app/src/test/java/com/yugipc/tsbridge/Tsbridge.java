package com.yugipc.tsbridge;

/** Test double of the private AAR's gomobile class (the real one only ships in private builds). */
public final class Tsbridge {
    public static int starts, stops, forwards;
    public static boolean running;

    private Tsbridge() {}

    public static void reset() { starts = stops = forwards = 0; running = false; }
    public static void addTCPForward(long local, long remote) { forwards++; }
    public static void start(String dir, String key, String host) { starts++; running = true; }
    public static void stop() { stops++; running = false; }
    public static boolean waitReady(long ms) { return running; }
    public static String state() { return running ? "Running" : "Stopped"; }
    public static String lastError() { return ""; }
    public static boolean hasBuiltinKey() { return true; }
}
