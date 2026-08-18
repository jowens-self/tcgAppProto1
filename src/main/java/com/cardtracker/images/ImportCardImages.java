package com.cardtracker.images;

import com.cardtracker.Env;
import com.cardtracker.source.pokemontcg.PokemonTcgCatalog;
import com.cardtracker.source.pokemontcg.PokemonTcgCatalog.Card;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches our Pokemon products against the pokemontcg.io catalog and caches a
 * downscaled thumbnail on local disk.
 *
 * Run it:
 *   java -cp target/cardtracker-0.1.0.jar com.cardtracker.images.ImportCardImages
 *   ... [--limit N] [--group GROUP_ID] [--match-only]
 *
 * **Why images are cached locally rather than hotlinked.** The browser must
 * never contact a third party for these - our own API serves them off disk.
 * That was the rule that got broken once already with TCGplayer's CDN (see
 * CLAUDE.md), and it applies to a permitted host too: fetch once, serve
 * forever, no per-view traffic to anyone.
 *
 * **Coverage runs ~60% - but that number is NOT a ceiling, it is a bug.**
 * Measured: 16,841 of 27,807 Pokemon singles matched (60.6%). An earlier
 * version of this comment blamed the gap on the upstream catalog, claiming
 * "World Championship decks / jumbo / prize packs / promos are absent
 * entirely." **That claim is false** - audited 2026-08-18 against the
 * cached catalog: `swshp` alone carries 304 promo cards we had written off.
 * The real causes are two normalization defects below - see
 * DEVELOPMENT_HISTORY.md Part 21. Replayed offline, fixing both recovers
 * 8,834 of the 10,912 no_match rows (81%).
 *
 * 1. {@link #LEADING_DIGITS} is anchored, so {@link #numberDigits} returns
 *    null for any number not starting with a digit (H1, SV066, SWSH014,
 *    RC4, TG12, GG46). That null is fatal on BOTH sides: 1,550 of 20,479
 *    catalog cards (7.6%) are never indexed, and those products never
 *    attempt a match.
 * 2. {@link #normName} strips a trailing "- 123" and "- 4/5" but not a
 *    trailing parenthetical or letter-number token, so "Alakazam (H1)" and
 *    "Rillaboom V - SWSH014" never match their bare catalog names.
 *
 * Not yet fixed: ~half the recoverable matches come from a different set
 * than the product's own (championship stamps, Cracked Ice holos, Poke Ball
 * patterns, jumbos), and whether a variant may show its base printing's
 * image is the owner's call, still open.
 *
 * Misses are recorded as status='no_match' so they are never re-queried -
 * which is why fixing the above also requires clearing those rows.
 */
public class ImportCardImages {

    /** Pokemon only - no permitted image source exists for any other game. */
    private static final int POKEMON = 3;

    /** Rendered at 40x56 CSS px; 160 wide covers 2x displays with headroom. */
    private static final int THUMB_WIDTH = 160;
    private static final float JPEG_QUALITY = 0.82f;

    public static void main(String[] args) throws Exception {
        int limit = Integer.MAX_VALUE;
        Integer onlyGroup = null;
        boolean matchOnly = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--limit" -> limit = Integer.parseInt(args[++i]);
                case "--group" -> onlyGroup = Integer.parseInt(args[++i]);
                case "--match-only" -> matchOnly = true;
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }

        Path imageDir = Path.of(Env.get("CARDTRACKER_IMAGE_DIR", "images"));
        Files.createDirectories(imageDir);

        String url = Env.get("CARDTRACKER_DB_URL", "jdbc:postgresql://localhost:5432/cardtracker");
        String user = Env.get("CARDTRACKER_DB_USER", "cardtracker");
        String pass = Env.get("CARDTRACKER_DB_PASS", "localdev");

        PokemonTcgCatalog api = new PokemonTcgCatalog();

        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            List<Product> todo = productsNeedingImages(conn, onlyGroup, limit);
            if (todo.isEmpty()) {
                // Distinguish "there is nothing pending" from "you asked for
                // none" - the old message claimed the former in both cases.
                System.out.println(limit <= 0
                        ? "Nothing selected: --limit " + limit + " requests no products."
                        : "Nothing pending - every matching Pokemon single already has a "
                          + "card_images row. Delete rows to force a re-check.");
                return;
            }
            System.out.printf("%d product(s) to check%n", todo.size());

            Map<String, List<Card>> byKeyStrict = new HashMap<>();
            Map<String, List<Card>> byKeyLoose = new HashMap<>();
            CatalogCache cache = new CatalogCache(imageDir.resolve(".catalog-cache"));

            List<Card> catalog = cache.load();
            List<String> failedSets = new ArrayList<>();
            if (catalog != null) {
                System.out.printf("Using cached catalog (%d cards, age %s). "
                        + "Delete %s to force a refresh.%n",
                        catalog.size(), cache.ageDescription(), cache.file());
            } else {
                System.out.println("Downloading upstream catalog (per set - their deep "
                        + "pagination 500s past ~12k rows)...");
                catalog = new ArrayList<>();
                List<String> setIds = api.setIds();
                List<String> pending = new ArrayList<>(setIds);

                // Up to three sweeps over whatever is still missing. Their API
                // fails 5-11 sets out of 174 on any given attempt, essentially
                // at random, and a set that fails once usually succeeds moments
                // later. Without this the run almost never reaches a complete
                // catalog - and an incomplete catalog blocks miss-recording
                // entirely (see below), so every future run then re-checks all
                // ~11,700 unmatched products from scratch, forever.
                for (int sweep = 1; sweep <= 3 && !pending.isEmpty(); sweep++) {
                    if (sweep > 1) {
                        System.out.printf("  retry sweep %d for %d set(s): %s%n",
                                sweep, pending.size(), pending);
                    }
                    List<String> stillFailing = new ArrayList<>();
                    for (int i = 0; i < pending.size(); i++) {
                        try {
                            catalog.addAll(api.cardsInSet(pending.get(i)));
                        } catch (Exception e) {
                            stillFailing.add(pending.get(i));
                            if (sweep == 3) {
                                System.out.printf("  set %s failed after 3 sweeps: %s%n",
                                        pending.get(i), e.getMessage());
                            }
                        }
                        if (sweep == 1 && (i + 1) % 25 == 0) {
                            System.out.printf("  %d/%d sets, %d cards%n",
                                    i + 1, pending.size(), catalog.size());
                        }
                        api.pace();
                    }
                    pending = stillFailing;
                }
                failedSets = pending;
                if (failedSets.isEmpty()) cache.save(catalog);
            }

            for (Card c : catalog) {
                String n = normName(c.name());
                String d = numberDigits(c.number());
                if (n == null || d == null) continue;
                byKeyStrict.computeIfAbsent(n + "|" + d + "|" + c.printedTotal(),
                        k -> new ArrayList<>()).add(c);
                byKeyLoose.computeIfAbsent(n + "|" + d, k -> new ArrayList<>()).add(c);
            }

            // If any set is still missing, the catalog is incomplete - and a
            // product that looks unmatched might simply live in a set we never
            // saw. Writing 'no_match' then would be a permanent lie: those rows
            // are never re-checked, so a transient 500 would silently cost that
            // card its image forever. Unmatched products are left with no row
            // at all in that case, so a later run reconsiders them.
            boolean catalogComplete = failedSets.isEmpty();
            System.out.printf("Catalog: %d cards%s%n", catalog.size(),
                    catalogComplete ? "" : " (INCOMPLETE - misses will NOT be recorded)");
            if (!catalogComplete) {
                System.out.println("  still failing after 3 sweeps: " + failedSets
                        + "\n  re-run later to pick them up.");
            }

            int matched = 0, noMatch = 0, downloaded = 0, failed = 0, skippedUnknown = 0;
            for (Product p : todo) {
                String n = normName(p.name);
                String d = numberDigits(p.cardNumber);
                String t = numberTotal(p.cardNumber);

                Card hit = null;
                String rule = null;
                if (n != null && d != null) {
                    if (t != null) {
                        List<Card> c = byKeyStrict.get(n + "|" + d + "|" + t);
                        if (c != null && !c.isEmpty()) {
                            hit = c.get(0);
                            rule = "name+number+total";
                        }
                    }
                    if (hit == null) {
                        List<Card> c = byKeyLoose.get(n + "|" + d);
                        // Only accept a loose match when it is unambiguous - a
                        // wrong image is worse than no image for someone
                        // pricing inventory.
                        if (c != null && c.size() == 1) {
                            hit = c.get(0);
                            rule = "name+number";
                        }
                    }
                }

                if (hit == null || hit.imageUrl() == null) {
                    if (catalogComplete) {
                        record(conn, p.productId, null, "no_match", null, null, null);
                        noMatch++;
                    } else {
                        skippedUnknown++;
                    }
                    continue;
                }
                matched++;
                if (matchOnly) {
                    record(conn, p.productId, hit.id(), "failed", null, null, rule);
                    continue;
                }

                try {
                    byte[] raw = api.download(hit.imageUrl());
                    byte[] thumb = toThumbnail(raw);
                    String rel = POKEMON + "/" + p.productId + ".jpg";
                    Path dest = imageDir.resolve(rel);
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, thumb);
                    record(conn, p.productId, hit.id(), "ok", rel, thumb.length, rule);
                    downloaded++;
                    if (downloaded % 100 == 0) {
                        System.out.printf("  %d downloaded, %d no-match, %d failed%n",
                                downloaded, noMatch, failed);
                    }
                } catch (Exception e) {
                    // 'failed' rather than 'no_match': the card exists upstream,
                    // so this is retryable on a later run.
                    record(conn, p.productId, hit.id(), "failed", null, null, rule);
                    failed++;
                }
                api.pace();
            }

            System.out.printf("Done. matched=%d (downloaded=%d, failed=%d), no_match=%d%n",
                    matched, downloaded, failed, noMatch);
            if (skippedUnknown > 0) {
                System.out.printf("  %d unmatched product(s) left unrecorded because the "
                        + "catalog was incomplete - re-run to resolve them.%n", skippedUnknown);
            }
        }
    }

    private record Product(int productId, String name, String cardNumber) {
    }

    /**
     * Pokemon singles with no usable card_images row yet. 'failed' rows ARE
     * retried; 'ok' and 'no_match' are not - that negative caching is what
     * stops ~11,000 unmatchable cards being re-checked on every run.
     */
    private static List<Product> productsNeedingImages(Connection conn, Integer group, int limit)
            throws Exception {
        StringBuilder sql = new StringBuilder("""
                SELECT p.product_id, p.name, extended_field(p.extended_data, 'Number') AS num
                  FROM products p
                  LEFT JOIN card_images ci ON ci.product_id = p.product_id
                 WHERE p.category_id = ? AND p.is_sealed = FALSE
                   AND (ci.product_id IS NULL OR ci.status = 'failed')
                """);
        if (group != null) sql.append(" AND p.group_id = ").append(group);
        sql.append(" ORDER BY p.product_id LIMIT ").append(limit);

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            ps.setInt(1, POKEMON);
            List<Product> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Product(rs.getInt(1), rs.getString(2), rs.getString(3)));
                }
            }
            return out;
        }
    }

    private static void record(Connection conn, int productId, String sourceId, String status,
                               String path, Integer bytes, String rule) throws Exception {
        String sql = """
                INSERT INTO card_images (product_id, source_id, status, file_path, bytes,
                                         match_rule, checked_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (product_id) DO UPDATE SET
                    source_id = EXCLUDED.source_id, status = EXCLUDED.status,
                    file_path = EXCLUDED.file_path, bytes = EXCLUDED.bytes,
                    match_rule = EXCLUDED.match_rule, checked_at = now()
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, productId);
            ps.setString(2, sourceId);
            ps.setString(3, status);
            ps.setString(4, path);
            if (bytes == null) ps.setNull(5, java.sql.Types.INTEGER); else ps.setInt(5, bytes);
            ps.setString(6, rule);
            ps.executeUpdate();
        }
    }

    /**
     * Downscale to a real thumbnail. Their "small" PNG is ~157KB for
     * something rendered at 40x56 - storing it as-is would be ~2.6GB across
     * the catalog, versus roughly 200MB like this.
     */
    static byte[] toThumbnail(byte[] raw) throws Exception {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
        if (src == null) throw new IllegalArgumentException("unreadable image");
        int w = THUMB_WIDTH;
        int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));

        // TYPE_INT_RGB, not ARGB: JPEG has no alpha channel, and writing an
        // image that has one produces colour-inverted output on some encoders.
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(dst, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    // --- matching helpers -------------------------------------------------
    // Ported from the throwaway script used to measure the match rate before
    // committing to this, so the numbers in the class comment are the numbers
    // these rules actually produce.

    private static final Pattern TRAILING_FRACTION = Pattern.compile("\\s*-\\s*[0-9a-z]+/[0-9a-z]+\\s*$");
    private static final Pattern TRAILING_NUMBER = Pattern.compile("\\s*-\\s*[0-9]+\\s*$");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9 ]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\s*([0-9]+)");

    /** "Mew ex - 232/091" -> "mew ex", so it lines up with their bare names. */
    static String normName(String s) {
        if (s == null) return null;
        String v = s.toLowerCase(Locale.ROOT);
        v = TRAILING_FRACTION.matcher(v).replaceAll("");
        v = TRAILING_NUMBER.matcher(v).replaceAll("");
        v = NON_ALNUM.matcher(v).replaceAll(" ");
        v = SPACES.matcher(v).replaceAll(" ").trim();
        return v.isEmpty() ? null : v;
    }

    /** "004/102" -> "4"; "196" -> "196". Leading zeros are padding, not data. */
    static String numberDigits(String s) {
        if (s == null) return null;
        String head = s.split("/")[0];
        Matcher m = LEADING_DIGITS.matcher(head);
        return m.find() ? String.valueOf(Integer.parseInt(m.group(1))) : null;
    }

    /** "004/102" -> "102"; anything without a denominator -> null. */
    static String numberTotal(String s) {
        if (s == null) return null;
        String[] parts = s.split("/");
        if (parts.length != 2) return null;
        Matcher m = LEADING_DIGITS.matcher(parts[1]);
        return m.find() ? String.valueOf(Integer.parseInt(m.group(1))) : null;
    }
}
