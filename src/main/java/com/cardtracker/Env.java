package com.cardtracker;

public final class Env {

    private Env() {
    }

    public static String get(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
