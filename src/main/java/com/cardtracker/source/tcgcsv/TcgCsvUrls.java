package com.cardtracker.source.tcgcsv;

/**
 * Endpoint shapes for tcgcsv.com, per its documented API. Verified against
 * live responses - see README "Verified against live data" - and proven by
 * every daily pull and the full 2024-02-08-onward archive backfill.
 */
public final class TcgCsvUrls {

    private static final String BASE = "https://tcgcsv.com/tcgplayer";

    public static final int POKEMON = 3;

    private TcgCsvUrls() {
    }

    public static String categories() {
        return BASE + "/categories";
    }

    public static String groups(int categoryId) {
        return BASE + "/" + categoryId + "/groups";
    }

    public static String products(int categoryId, int groupId) {
        return BASE + "/" + categoryId + "/" + groupId + "/products";
    }

    public static String prices(int categoryId, int groupId) {
        return BASE + "/" + categoryId + "/" + groupId + "/prices";
    }
}
