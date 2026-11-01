package com.mangaverse.provider.comicvine;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import com.mangaverse.common.Json;
import com.mangaverse.common.RateLimiter;
import com.mangaverse.common.TextNormalizer;
import com.mangaverse.config.AppProperties;
import com.mangaverse.config.HttpSupport;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.ProviderCapabilities;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.provider.spi.RemoteTitleDetails;
import com.mangaverse.provider.spi.SearchQuery;

import tools.jackson.databind.JsonNode;

/**
 * Provedor de metadados de HQs via Comic Vine API (uso não comercial, chave própria obrigatória).
 * Não fornece páginas nem downloads: apenas séries (volumes) e edições (issues).
 */
public class ComicVineProvider implements ContentProvider {

    public static final String CODE = "comicvine";
    private static final String VOLUME_FIELDS = "id,name,publisher,start_year,image,deck,description,count_of_issues,site_detail_url";

    private final AppProperties.ComicVine config;
    private final HttpSupport http;
    private final WebClient api;
    private final RateLimiter limiter;

    public ComicVineProvider(AppProperties.ComicVine config, HttpSupport http) {
        this(config, http, http.client(config.baseUrl()));
    }

    ComicVineProvider(AppProperties.ComicVine config, HttpSupport http, WebClient api) {
        this.config = config;
        this.http = http;
        this.api = api;
        this.limiter = new RateLimiter(config.requestsPerSecond());
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String displayName() {
        return "Comic Vine";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return ProviderCapabilities.metadata();
    }

    @Override
    public List<RemoteTitle> search(SearchQuery query) {
        if (!query.types().isEmpty() && !query.types().contains(ContentType.COMIC)) {
            return List.of();
        }
        JsonNode body = get(b -> b.path("/search/")
                .queryParam("query", query.text())
                .queryParam("resources", "volume")
                .queryParam("limit", Math.min(Math.max(query.limit(), 1), 50))
                .queryParam("field_list", VOLUME_FIELDS)
                .build());
        return Json.array(body, "results").stream().map(this::toRemoteTitle).toList();
    }

    @Override
    public RemoteTitleDetails getDetails(String externalId) {
        String id = requireNumeric(externalId);
        JsonNode body = get(b -> b.path("/volume/4050-{id}/")
                .queryParam("field_list", VOLUME_FIELDS + ",characters,people")
                .build(id));
        JsonNode v = body.path("results");
        List<String> characters = Json.array(v, "characters").stream()
                .map(c -> Json.text(c, "name")).filter(n -> n != null).limit(40).toList();
        List<String> people = Json.array(v, "people").stream()
                .map(c -> Json.text(c, "name")).filter(n -> n != null).limit(20).toList();
        String description = Json.text(v, "deck");
        String longDescription = TextNormalizer.stripHtml(Json.text(v, "description"));
        return new RemoteTitleDetails(CODE, id, Json.text(v, "name"), List.of(),
                longDescription != null ? longDescription : description, image(v), ContentType.COMIC,
                people, List.of(), Json.text(v.path("publisher"), "name"), List.of(), characters,
                Set.of("en"), "en", null, Json.integer(v, "start_year"), Json.text(v, "site_detail_url"));
    }

    @Override
    public List<RemoteChapter> getChapters(String externalId, Set<String> languages) {
        String id = requireNumeric(externalId);
        List<RemoteChapter> issues = new ArrayList<>();
        int offset = 0;
        int total;
        do {
            final int currentOffset = offset;
            JsonNode body = get(b -> b.path("/issues/")
                    .queryParam("filter", "volume:" + id)
                    .queryParam("sort", "cover_date:asc")
                    .queryParam("limit", 100)
                    .queryParam("offset", currentOffset)
                    .queryParam("field_list", "id,name,issue_number,cover_date,store_date,site_detail_url")
                    .build());
            for (JsonNode issue : Json.array(body, "results")) {
                issues.add(toIssue(issue));
            }
            total = body.path("number_of_total_results").asInt(0);
            offset += 100;
        } while (offset < total && offset < 2000);
        return issues;
    }

    @Override
    public Set<String> getAvailableLanguages(String externalId) {
        return Set.of("en");
    }

    @Override
    public ProviderHealth health() {
        if (!config.configured()) {
            return ProviderHealth.notConfigured("Defina COMICVINE_API_KEY para habilitar o Comic Vine.");
        }
        try {
            get(b -> b.path("/types/").build());
            return ProviderHealth.online("API respondendo");
        } catch (ProviderException e) {
            return ProviderHealth.offline(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode get(Function<UriBuilder, URI> uri) {
        if (!config.configured()) {
            throw new ProviderException(CODE, "Comic Vine não configurado: defina COMICVINE_API_KEY.");
        }
        limiter.acquire();
        JsonNode body = http.execute(CODE, api.get().uri(b -> {
            b.queryParam("api_key", config.apiKey()).queryParam("format", "json");
            return uri.apply(b);
        }).retrieve().bodyToMono(JsonNode.class));
        int status = body == null ? -1 : body.path("status_code").asInt(-1);
        return switch (status) {
            case 1 -> body;
            case 100 -> throw new ProviderException(CODE, "Chave da API Comic Vine inválida.");
            case 107 -> throw new ProviderException(CODE, "Limite de requisições do Comic Vine atingido.");
            case 101 -> throw new ProviderException(CODE, "Registro não encontrado no Comic Vine.");
            default -> throw new ProviderException(CODE, "Erro do Comic Vine: " + (body == null ? "sem resposta" : Json.text(body, "error")));
        };
    }

    RemoteTitle toRemoteTitle(JsonNode v) {
        String deck = Json.text(v, "deck");
        String description = deck != null ? deck : TextNormalizer.stripHtml(Json.text(v, "description"));
        if (description != null && description.length() > 600) {
            description = description.substring(0, 600) + "…";
        }
        return new RemoteTitle(CODE, displayName(), Json.text(v, "id"), Json.text(v, "name"), List.of(),
                description, image(v), ContentType.COMIC, Set.of("en"), Json.integer(v, "start_year"),
                null, Json.text(v, "site_detail_url"), Json.text(v.path("publisher"), "name"));
    }

    RemoteChapter toIssue(JsonNode issue) {
        String number = Json.text(issue, "issue_number");
        BigDecimal parsed = null;
        if (number != null) {
            try {
                parsed = new BigDecimal(number.trim());
            } catch (NumberFormatException ignored) {
                // números como "1A" ficam apenas no rótulo
            }
        }
        String date = Json.text(issue, "store_date");
        if (date == null) {
            date = Json.text(issue, "cover_date");
        }
        return new RemoteChapter(Json.text(issue, "id"), parsed, number, null, Json.text(issue, "name"), "en",
                null, parseDate(date), null, false, false, Json.text(issue, "site_detail_url"));
    }

    private static String image(JsonNode v) {
        JsonNode image = v.path("image");
        String url = Json.text(image, "medium_url");
        return url != null ? url : Json.text(image, "original_url");
    }

    private static Instant parseDate(String date) {
        if (date == null) {
            return null;
        }
        try {
            return LocalDate.parse(date.substring(0, Math.min(10, date.length()))).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String requireNumeric(String id) {
        if (id == null || !id.matches("\\d{1,12}")) {
            throw new ProviderException(CODE, "Identificador Comic Vine inválido.");
        }
        return id;
    }
}
