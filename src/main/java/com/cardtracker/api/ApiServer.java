package com.cardtracker.api;

import com.cardtracker.Env;
import com.cardtracker.db.QueryRepository;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.NotFoundResponse;
import io.javalin.json.JavalinJackson;

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

    private static LocalDate dateParam(String raw, String name) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            throw new BadRequestResponse(name + " must be YYYY-MM-DD, got '" + raw + "'");
        }
    }
}
