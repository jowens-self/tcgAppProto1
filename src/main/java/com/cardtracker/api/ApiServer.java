package com.cardtracker.api;

import com.cardtracker.Env;
import com.cardtracker.db.QueryRepository;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.json.JavalinJackson;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.function.Function;

/**
 * Read-only HTTP API over the price history.
 *
 * Exists so a front end never talks to Postgres (or to tcgcsv) directly -
 * see CLAUDE.md "Architecture intent". Strictly read-only: there is no
 * endpoint here that writes, and ingestion stays in App/BackfillArchive
 * where the append-only guarantees live.
 *
 * Run it:
 *   java -cp target/cardtracker-0.1.0.jar com.cardtracker.api.ApiServer
 */
public class ApiServer {

    public static final int DEFAULT_PORT = 7070;

    public static void main(String[] args) {
        String url = Env.get("CARDTRACKER_DB_URL", "jdbc:postgresql://localhost:5432/cardtracker");
        String user = Env.get("CARDTRACKER_DB_USER", "cardtracker");
        String pass = Env.get("CARDTRACKER_DB_PASS", "localdev");
        int port = Integer.parseInt(Env.get("CARDTRACKER_API_PORT", String.valueOf(DEFAULT_PORT)));
        // Same default as ImportCardImages, which writes them.
        Path imageRoot = Path.of(Env.get("CARDTRACKER_IMAGE_DIR", "images"))
                .toAbsolutePath().normalize();

        Javalin app = Javalin.create(config -> {
            // LocalDate must serialize as "2026-08-14", not an array of ints
            // or an epoch number - the client charts on these directly.
            config.jsonMapper(new JavalinJackson().updateMapper(m -> {
                m.registerModule(new JavaTimeModule());
                m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
            }));
            // The Angular dev server runs on its own port, so browser calls
            // here are cross-origin and blocked without this. Scoped to
            // localhost deliberately - this API is local-only and read-only,
            // but that's no reason to let any site on the internet query it
            // from a visitor's browser.
            config.bundledPlugins.enableCors(cors -> cors.addRule(rule -> {
                rule.allowHost("http://localhost:4200", "http://127.0.0.1:4200");
            }));
        });

        app.get("/api/health", ctx -> ctx.json(Map.of("status", "ok")));

        app.get("/api/games", ctx ->
                ctx.json(withRepo(url, user, pass, QueryRepository::games)));

        app.get("/api/games/{categoryId}/sets", ctx -> {
            int id = pathId(ctx.pathParam("categoryId"));
            ctx.json(withRepo(url, user, pass, repo -> repo.sets(id)));
        });

        // Every set of a game, with current value and windowed return.
        //   ?game=3&days=30&asOf=2026-08-18
        // game omitted = all tracked games. days defaults to 30 in
        // game_set_overview() rather than here, so psql callers match.
        app.get("/api/sets/overview", ctx -> {
            Integer days = intParam(ctx.queryParam("days"), "days");
            if (days != null && days < 1) {
                throw new BadRequestResponse("days must be at least 1");
            }
            ctx.json(withRepo(url, user, pass, repo -> repo.setOverview(
                    intParam(ctx.queryParam("game"), "game"),
                    days,
                    dateParam(ctx.queryParam("asOf"), "asOf"))));
        });

        // Biggest movers over a window.
        //   ?days=7&game=3&set=23353&sealed=false&minPrice=1&direction=down
        //   &smoothing=3&limit=50
        // Every parameter is optional: the defaults (7 days, decliners,
        // singles, $1 floor, 3-day smoothing) are the screen's default view
        // and live in item_movers() so psql callers get them too.
        app.get("/api/movers", ctx -> {
            String dir = ctx.queryParam("direction");
            if (dir != null && !dir.equals("up") && !dir.equals("down")) {
                throw new BadRequestResponse("direction must be 'up' or 'down'");
            }
            Integer days = intParam(ctx.queryParam("days"), "days");
            if (days != null && days < 1) {
                throw new BadRequestResponse("days must be at least 1");
            }
            Integer smoothing = intParam(ctx.queryParam("smoothing"), "smoothing");
            if (smoothing != null && smoothing < 1) {
                throw new BadRequestResponse("smoothing must be at least 1");
            }
            java.math.BigDecimal minPrice = decimalParam(ctx.queryParam("minPrice"), "minPrice");

            ctx.json(withRepo(url, user, pass, repo -> repo.movers(
                    days,
                    intParam(ctx.queryParam("game"), "game"),
                    intParam(ctx.queryParam("set"), "set"),
                    "true".equalsIgnoreCase(ctx.queryParam("sealed")),
                    minPrice,
                    dir,
                    smoothing,
                    intParam(ctx.queryParam("limit"), "limit"))));
        });

        // Card / product search. q is required; everything else optional.
        //   ?q=charizard&game=3&sealed=false&limit=25&offset=0
        app.get("/api/items/search", ctx -> {
            String q = ctx.queryParam("q");
            if (q == null || q.isBlank()) {
                throw new BadRequestResponse("q is required");
            }
            Integer game = intParam(ctx.queryParam("game"), "game");
            boolean includeSealed = !"false".equalsIgnoreCase(ctx.queryParam("sealed"));
            int limit = intParam(ctx.queryParam("limit"), "limit") == null
                    ? 25 : intParam(ctx.queryParam("limit"), "limit");
            int offset = intParam(ctx.queryParam("offset"), "offset") == null
                    ? 0 : intParam(ctx.queryParam("offset"), "offset");

            ctx.json(withRepo(url, user, pass,
                    repo -> repo.search(q, game, includeSealed, limit, offset)));
        });

        // Cached card thumbnail, served off OUR disk. The browser must never
        // fetch an image from a third party - that is the rule that got broken
        // with TCGplayer's CDN (see CLAUDE.md), and it holds for the permitted
        // image source too: ImportCardImages fetches once, this serves forever.
        //
        // 404 is an ordinary outcome, not an error: about 40% of Pokemon
        // singles have no upstream image, and every One Piece product has
        // none, so the client shows a placeholder.
        app.get("/api/items/{productId}/image", ctx -> {
            int id = pathId(ctx.pathParam("productId"));
            String rel = withRepo(url, user, pass, repo -> repo.imagePath(id));
            if (rel == null) throw new NotFoundResponse("No image for product " + id);

            Path file = imageRoot.resolve(rel).normalize();
            // The path comes from our own DB, but normalize + containment check
            // means a bad row can never read outside the image directory.
            if (!file.startsWith(imageRoot) || !Files.isRegularFile(file)) {
                throw new NotFoundResponse("No image file for product " + id);
            }
            ctx.contentType("image/jpeg");
            // Immutable in practice: a card's art doesn't change, and the file
            // is only rewritten by an explicit re-import.
            ctx.header("Cache-Control", "public, max-age=604800");
            ctx.result(Files.readAllBytes(file));
        });

        app.get("/api/items/{productId}", ctx -> {
            int id = pathId(ctx.pathParam("productId"));
            var product = withRepo(url, user, pass, repo -> repo.product(id));
            if (product == null) throw new NotFoundResponse("No product " + id);
            ctx.json(product);
        });

        app.get("/api/items/{productId}/variants", ctx -> {
            int id = pathId(ctx.pathParam("productId"));
            var variants = withRepo(url, user, pass, repo -> repo.variants(id));
            // An empty variant list means the id has no price history at all,
            // which for a client is the same failure as an unknown id - say so
            // rather than returning [] and letting a UI render a blank chart.
            if (variants.isEmpty()) {
                throw new NotFoundResponse("No price history for product " + id);
            }
            ctx.json(variants);
        });

        // ?subType=Holofoil&from=2024-02-08&to=2026-08-14
        // subType is required and deliberately has no default: picking one
        // for the caller would mean guessing which market they meant, and
        // Normal/Holofoil/Reverse Holofoil are genuinely different markets.
        app.get("/api/items/{productId}/history", ctx -> {
            int id = pathId(ctx.pathParam("productId"));
            String subType = ctx.queryParam("subType");
            if (subType == null || subType.isBlank()) {
                throw new BadRequestResponse(
                        "subType is required - call /api/items/" + id + "/variants to list them");
            }
            LocalDate from = dateParam(ctx.queryParam("from"), "from");
            LocalDate to = dateParam(ctx.queryParam("to"), "to");
            if (from != null && to != null && from.isAfter(to)) {
                throw new BadRequestResponse("from must not be after to");
            }

            var points = withRepo(url, user, pass, repo -> repo.history(id, subType, from, to));
            if (points.isEmpty()) {
                throw new NotFoundResponse(
                        "No history for product " + id + " variant '" + subType + "'");
            }
            ctx.json(points);
        });

        app.start(port);
        System.out.printf("CardTracker API on http://localhost:%d  (read-only)%n", port);
    }

    /**
     * One connection per request, closed on the way out.
     *
     * Not a pool on purpose: a JDBC Connection is not thread-safe, Javalin
     * serves requests concurrently, and a single shared Connection would give
     * intermittent corruption under any parallel use rather than a clean
     * failure. Opening one per request costs a few ms locally, which is the
     * same order as the queries themselves. If this ever moves off localhost
     * or serves more than one person, put HikariCP here - this method is the
     * only place that would change.
     */
    private static <T> T withRepo(String url, String user, String pass,
                                  SqlFunction<QueryRepository, T> work) throws Exception {
        try (Connection conn = DriverManager.getConnection(url, user, pass)) {
            return work.apply(new QueryRepository(conn));
        }
    }

    /** Like {@link Function} but allowed to throw, since JDBC does. */
    @FunctionalInterface
    private interface SqlFunction<A, R> {
        R apply(A a) throws Exception;
    }

    private static int pathId(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new BadRequestResponse("productId must be an integer, got '" + raw + "'");
        }
    }

    private static Integer intParam(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            throw new BadRequestResponse(name + " must be an integer, got '" + raw + "'");
        }
    }

    private static java.math.BigDecimal decimalParam(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return new java.math.BigDecimal(raw);
        } catch (NumberFormatException e) {
            throw new BadRequestResponse(name + " must be a number, got '" + raw + "'");
        }
    }

    private static LocalDate dateParam(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BadRequestResponse(name + " must be YYYY-MM-DD, got '" + raw + "'");
        }
    }
}
