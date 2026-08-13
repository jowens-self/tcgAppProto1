package com.cardtracker.source.tcgcsv;

import com.cardtracker.model.Category;
import com.cardtracker.model.Group;
import com.cardtracker.model.PriceSnapshot;
import com.cardtracker.model.Product;
import com.cardtracker.source.PriceSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads from tcgcsv.com, a third-party daily mirror of TCGplayer pricing.
 *
 * Note what this class does NOT do: it never contacts TCGplayer, never
 * authenticates, and never touches a seller account. All traffic goes to the
 * mirror's own infrastructure.
 */
public class TcgCsvPriceSource implements PriceSource {

    /**
     * The mirror asks for a descriptive User-Agent and blocks generic or
     * missing ones. Bump the version when behaviour changes so they can
     * identify us if we ever misbehave.
     */
    private static final String USER_AGENT = "CardTracker/0.1.0";

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Throttle throttle;

    public TcgCsvPriceSource(Throttle throttle) {
        this.throttle = throttle;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "tcgcsv";
    }

    public int requestsUsed() {
        return throttle.used();
    }

    /** All categories the mirror knows about (Pokemon, Magic, etc). */
    public List<Category> categories() throws Exception {
        JsonNode results = getResults(TcgCsvUrls.categories());
        List<Category> out = new ArrayList<>();
        for (JsonNode n : results) {
            out.add(new Category(
                    n.path("categoryId").asInt(),
                    n.path("name").asText(null),
                    n.path("modifiedOn").asText(null)));
        }
        return out;
    }

    /** All sets in a category. Pokemon is category 3. */
    public List<Group> groups(int categoryId) throws Exception {
        JsonNode results = getResults(TcgCsvUrls.groups(categoryId));
        List<Group> out = new ArrayList<>();
        for (JsonNode n : results) {
            out.add(new Group(
                    n.path("groupId").asInt(),
                    categoryId,
                    n.path("name").asText(null),
                    n.path("abbreviation").asText(null),
                    n.path("isSupplemental").asBoolean(false),
                    n.path("publishedOn").asText(null),
                    n.path("modifiedOn").asText(null)));
        }
        return out;
    }

    /** Every card (and sealed product) in a group, with its name. */
    public List<Product> products(int categoryId, int groupId) throws Exception {
        JsonNode results = getResults(TcgCsvUrls.products(categoryId, groupId));
        List<Product> out = new ArrayList<>();
        for (JsonNode n : results) {
            out.add(new Product(
                    n.path("productId").asInt(),
                    groupId,
                    categoryId,
                    n.path("name").asText(null),
                    n.path("cleanName").asText(null),
                    n.path("imageUrl").asText(null),
                    n.path("url").asText(null),
                    n.path("modifiedOn").asText(null),
                    mapper.writeValueAsString(n.path("extendedData"))));
        }
        return out;
    }

    @Override
    public List<PriceSnapshot> pricesForGroup(int categoryId, int groupId, LocalDate snapshotDate)
            throws Exception {
        JsonNode results = getResults(TcgCsvUrls.prices(categoryId, groupId));
        return toPriceSnapshots(results, snapshotDate);
    }

    /**
     * Shared with BackfillArchive, which parses the same JSON shape from
     * extracted archive files instead of a live response - one mapping,
     * used both places, so the two can't drift apart.
     */
    public static List<PriceSnapshot> toPriceSnapshots(JsonNode results, LocalDate snapshotDate) {
        List<PriceSnapshot> out = new ArrayList<>();
        for (JsonNode n : results) {
            out.add(new PriceSnapshot(
                    n.path("productId").asInt(),
                    // Normal / Holofoil / Reverse Holofoil / 1st Edition.
                    // Part of the primary key - never collapse these together.
                    n.path("subTypeName").asText("Unknown"),
                    snapshotDate,
                    decimal(n, "marketPrice"),
                    decimal(n, "lowPrice"),
                    decimal(n, "midPrice"),
                    decimal(n, "highPrice"),
                    decimal(n, "directLowPrice")));
        }
        return out;
    }

    private JsonNode getResults(String url) throws Exception {
        throttle.acquire();

        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();

        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

        if (res.statusCode() == 429 || res.statusCode() == 403) {
            throw new IllegalStateException(
                    "Mirror returned " + res.statusCode() + " for " + url
                    + ". You are likely throttled - back off for at least ten minutes "
                    + "and check the request rate before retrying.");
        }
        if (res.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + res.statusCode() + " for " + url);
        }

        JsonNode root = mapper.readTree(res.body());
        if (!root.path("success").asBoolean(false)) {
            throw new IllegalStateException("Mirror reported failure for " + url
                    + ": " + root.path("errors").toString());
        }
        return root.path("results");
    }

    /** Null-safe: absent and null both become null rather than 0.00, which would be a lie. */
    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return null;
        return v.decimalValue();
    }
}
