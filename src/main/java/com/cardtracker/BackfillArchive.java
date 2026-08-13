package com.cardtracker;

import com.cardtracker.db.PriceRepository;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.source.tcgcsv.TcgCsvPriceSource;
import com.cardtracker.source.tcgcsv.TcgCsvUrls;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

/**
 * One-time (or resumable) backfill from tcgcsv's daily archives, covering
 * 2024-02-08 onward. Each archive bundles every TCG category for one day;
 * only Pokemon (category 3) is extracted and kept - see CLAUDE.md.
 *
 * Idempotent by day: before touching a date, checks ingest_runs for a prior
 * "ok" run with source "tcgcsv-archive". If found, skips without
 * downloading anything. So re-running this after a crash, or months later
 * to catch up, only does the days it doesn't already have.
 *
 * Requires the `7z` CLI on PATH (brew install p7zip). Not part of the daily
 * App run - this is a separate, occasional batch job.
 */
public final class BackfillArchive {

    public static final String SOURCE = "tcgcsv-archive";
    public static final LocalDate EARLIEST_ARCHIVE = LocalDate.of(2024, 2, 8);
    private static final int CATEGORY_ID = TcgCsvUrls.POKEMON;
    private static final String USER_AGENT = "CardTracker/0.1.0";

    public static void main(String[] args) throws Exception {
        LocalDate start = args.length > 0 ? LocalDate.parse(args[0]) : EARLIEST_ARCHIVE;
        // UTC, not host-local - matches App's snapshot_date convention.
        LocalDate end = args.length > 1 ? LocalDate.parse(args[1]) : LocalDate.now(ZoneOffset.UTC).minusDays(1);

        String url = env("CARDTRACKER_DB_URL", "jdbc:postgresql://localhost:5432/cardtracker");
        String user = env("CARDTRACKER_DB_USER", "cardtracker");
        String pass = env("CARDTRACKER_DB_PASS", "localdev");

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            run(start, end, new PriceRepository(conn));
        }
    }

    /**
     * Backfills [start, end] into repo, skipping any day that already has a
     * successful run (see PriceRepository.hasSuccessfulRun). Safe to call
     * with a wide range even when most of it is already covered - each
     * already-done day costs one indexed lookup, not a download. Used both
     * by main() for manual/full backfills and by App for its startup gap
     * catch-up.
     */
    public static void run(LocalDate start, LocalDate end, PriceRepository repo) throws Exception {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        ObjectMapper mapper = new ObjectMapper();
        Path workDir = Files.createTempDirectory("cardtracker-backfill");

        int skipped = 0, done = 0, failed = 0;
        try {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                if (repo.hasSuccessfulRun(SOURCE, date)) {
                    skipped++;
                    continue;
                }

                long runId = repo.startRun(SOURCE, date);
                int rowsForDay = 0;
                Path archive = workDir.resolve(date + ".ppmd.7z");
                Path extractDir = workDir.resolve(date + "-extracted");
                try {
                    downloadArchive(http, date, archive);
                    extract(archive, extractDir);

                    Path categoryDir = extractDir.resolve(date.toString()).resolve(String.valueOf(CATEGORY_ID));
                    if (Files.isDirectory(categoryDir)) {
                        try (var groupDirs = Files.list(categoryDir)) {
                            for (Path groupDir : (Iterable<Path>) groupDirs::iterator) {
                                Path pricesFile = groupDir.resolve("prices");
                                if (!Files.isRegularFile(pricesFile)) continue;

                                JsonNode root = mapper.readTree(Files.readString(pricesFile));
                                if (!root.path("success").asBoolean(false)) continue;

                                List<PriceSnapshot> snapshots =
                                        TcgCsvPriceSource.toPriceSnapshots(root.path("results"), date);
                                rowsForDay += repo.insertBatch(snapshots);
                            }
                        }
                    }

                    repo.finishRun(runId, "ok", 0, rowsForDay, null);
                    done++;
                    System.out.printf("%s: %d rows%n", date, rowsForDay);
                } catch (Exception e) {
                    repo.finishRun(runId, "failed", 0, rowsForDay,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                    failed++;
                    System.out.printf("%s: FAILED - %s%n", date, e.getMessage());
                } finally {
                    deleteRecursive(archive);
                    deleteRecursive(extractDir);
                }

                // Politeness pacing for the archive CDN - distinct from Throttle,
                // which enforces tcgcsv's documented live-API limits specifically.
                Thread.sleep(300);
            }
        } finally {
            deleteRecursive(workDir);
        }

        System.out.printf("%nDone. %d already had, %d backfilled, %d failed.%n", skipped, done, failed);
    }

    private static void downloadArchive(HttpClient http, LocalDate date, Path dest) throws Exception {
        String archiveUrl = "https://tcgcsv.com/archive/tcgplayer/prices-" + date + ".ppmd.7z";
        HttpRequest req = HttpRequest.newBuilder(URI.create(archiveUrl))
                .header("User-Agent", USER_AGENT)
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<Path> res = http.send(req, HttpResponse.BodyHandlers.ofFile(dest));
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + archiveUrl);
        }
    }

    private static void extract(Path archive, Path destDir) throws Exception {
        Files.createDirectories(destDir);
        Process p = new ProcessBuilder("7z", "x", archive.toString(), "-o" + destDir, "-y")
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes(); // drain so the process can't block on a full output pipe
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("7z exited " + exit + " for " + archive);
        }
    }

    private static void deleteRecursive(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ignored) {
                    // best-effort cleanup of our own temp dir
                }
            });
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
