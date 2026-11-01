package com.mangaverse.provider.mangadex;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mangaverse.common.Json;
import com.mangaverse.common.RateLimiter;
import com.mangaverse.config.AppProperties;
import com.mangaverse.config.HttpSupport;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.DiscoverKind;
import com.mangaverse.provider.spi.FetchedImage;
import com.mangaverse.provider.spi.PageRef;
import com.mangaverse.provider.spi.ProviderCapabilities;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.provider.spi.RemoteTitleDetails;
import com.mangaverse.provider.spi.SearchQuery;

import tools.jackson.databind.JsonNode;

/**
 * Adaptador para a API pública do MangaDex (https://api.mangadex.org/docs).
 * Respeita o limite global (~5 req/s) e reporta o resultado de downloads de imagens
 * servidas pela rede MangaDex@Home, conforme exigido pela documentação.
 */
public class MangaDexProvider implements ContentProvider {

    public static final String CODE = "mangadex";
    private static final Logger log = LoggerFactory.getLogger(MangaDexProvider.class);
    private static final List<String> ALL_RATINGS = List.of("safe", "suggestive", "erotica", "pornographic");
    private static final int FEED_PAGE = 500;

    private final AppProperties.MangaDex config;
    private final HttpSupport http;
    private final WebClient api;
    private final WebClient raw;
    private final RateLimiter apiLimiter;
    private final RateLimiter atHomeLimiter = new RateLimiter(0.6); // at-home/server: 40 req/min
    private final Cache<String, AtHome> atHomeCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10)).maximumSize(500).build();
    private volatile List<String> contentRatings = List.of("safe", "suggestive");

    public MangaDexProvider(AppProperties.MangaDex config, HttpSupport http) {
        this(config, http, http.client(config.baseUrl()), http.client(null));
    }

    MangaDexProvider(AppProperties.MangaDex config, HttpSupport http, WebClient api, WebClient raw) {
        this.config = config;
        this.http = http;
        this.api = api;
        this.raw = raw;
        this.apiLimiter = new RateLimiter(config.requestsPerSecond());
    }

    public void setContentRatings(List<String> ratings) {
        List<String> valid = ratings.stream().filter(ALL_RATINGS::contains).toList();
        this.contentRatings = valid.isEmpty() ? List.of("safe") : valid;
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String displayName() {
        return "MangaDex";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true, true, false, true);
    }

    @Override
    public List<RemoteTitle> search(SearchQuery query) {
        JsonNode body = get(b -> {
            b.path("/manga")
                    .queryParam("title", query.text())
                    .queryParam("limit", Math.min(Math.max(query.limit(), 1), 50))
                    .queryParam("includes[]", "cover_art")
                    .queryParam("order[relevance]", "desc");
            contentRatings.forEach(r -> b.queryParam("contentRating[]", r));
            originalLanguagesFor(query.types()).forEach(l -> b.queryParam("originalLanguage[]", l));
            return b.build();
        });
        return Json.array(body, "data").stream().map(this::toRemoteTitle).toList();
    }

    @Override
    public List<RemoteTitle> discover(DiscoverKind kind, Set<String> languages, int page) {
        int limit = 24;
        JsonNode body = get(b -> {
            b.path("/manga")
                    .queryParam("limit", limit)
                    .queryParam("offset", Math.max(0, page - 1) * limit)
                    .queryParam("includes[]", "cover_art")
                    .queryParam(kind == DiscoverKind.POPULAR ? "order[followedCount]" : "order[latestUploadedChapter]", "desc");
            contentRatings.forEach(r -> b.queryParam("contentRating[]", r));
            languages.forEach(l -> b.queryParam("availableTranslatedLanguage[]", l));
            return b.build();
        });
        return Json.array(body, "data").stream().map(this::toRemoteTitle).toList();
    }

    @Override
    public RemoteTitleDetails getDetails(String externalId) {
        requireUuid(externalId);
        JsonNode body = get(b -> b.path("/manga/{id}")
                .queryParam("includes[]", "cover_art")
                .queryParam("includes[]", "author")
                .queryParam("includes[]", "artist")
                .build(externalId));
        JsonNode m = body.path("data");
        JsonNode a = m.path("attributes");
        List<String> authors = new ArrayList<>();
        List<String> artists = new ArrayList<>();
        for (JsonNode rel : Json.array(m, "relationships")) {
            String name = Json.text(rel.path("attributes"), "name");
            if (name == null) {
                continue;
            }
            switch (Json.text(rel, "type")) {
                case "author" -> authors.add(name);
                case "artist" -> artists.add(name);
                default -> { }
            }
        }
        List<String> genres = new ArrayList<>();
        for (JsonNode tag : Json.array(a, "tags")) {
            String name = localized(tag.path("attributes").path("name"));
            if (name != null) {
                genres.add(name);
            }
        }
        String original = Json.text(a, "originalLanguage");
        return new RemoteTitleDetails(CODE, externalId, mainTitle(a), altTitles(a), localized(a.path("description")),
                coverUrl(m), ContentType.fromOriginalLanguage(original), authors, artists,
                null, genres, List.of(), languages(a), original, Json.text(a, "status"), Json.integer(a, "year"),
                "https://mangadex.org/title/" + externalId);
    }

    @Override
    public List<RemoteChapter> getChapters(String externalId, Set<String> languages) {
        requireUuid(externalId);
        List<RemoteChapter> chapters = new ArrayList<>();
        int offset = 0;
        int total;
        do {
            final int currentOffset = offset;
            JsonNode body = get(b -> {
                b.path("/manga/{id}/feed")
                        .queryParam("limit", FEED_PAGE)
                        .queryParam("offset", currentOffset)
                        .queryParam("includes[]", "scanlation_group")
                        .queryParam("order[volume]", "asc")
                        .queryParam("order[chapter]", "asc");
                ALL_RATINGS.forEach(r -> b.queryParam("contentRating[]", r));
                languages.forEach(l -> b.queryParam("translatedLanguage[]", l));
                return b.build(externalId);
            });
            for (JsonNode c : Json.array(body, "data")) {
                chapters.add(toChapter(c));
            }
            total = body.path("total").asInt(0);
            offset += FEED_PAGE;
        } while (offset < total && offset < 10_000);
        return chapters;
    }

    @Override
    public Set<String> getAvailableLanguages(String externalId) {
        requireUuid(externalId);
        JsonNode body = get(b -> b.path("/manga/{id}").build(externalId));
        return languages(body.path("data").path("attributes"));
    }

    @Override
    public List<PageRef> getPages(String chapterExternalId) {
        requireUuid(chapterExternalId);
        AtHome atHome = atHome(chapterExternalId);
        List<PageRef> pages = new ArrayList<>();
        for (int i = 0; i < atHome.files().size(); i++) {
            pages.add(new PageRef(i, atHome.baseUrl() + "/data/" + atHome.hash() + "/" + atHome.files().get(i)));
        }
        return pages;
    }

    @Override
    public FetchedImage fetchPage(PageRef page) {
        URI uri = URI.create(page.url());
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (!host.endsWith(".mangadex.network") && !host.endsWith("mangadex.org")) {
            throw new ProviderException(CODE, "Host de imagem não permitido: " + host);
        }
        long start = System.nanoTime();
        try {
            ResponseEntity<byte[]> response = http.execute(CODE, raw.get().uri(uri)
                    .accept(MediaType.ALL).retrieve().toEntity(byte[].class), 1);
            byte[] data = response.getBody() == null ? new byte[0] : response.getBody();
            report(uri, host, true, data.length, start, "HIT".equalsIgnoreCase(response.getHeaders().getFirst("X-Cache")));
            String type = response.getHeaders().getContentType() == null ? "image/jpeg" : response.getHeaders().getContentType().toString();
            return new FetchedImage(data, type);
        } catch (ProviderException e) {
            report(uri, host, false, 0, start, false);
            atHomeCache.invalidateAll(); // força novo servidor na próxima tentativa
            throw e;
        }
    }

    @Override
    public ProviderHealth health() {
        try {
            String pong = http.execute(CODE, api.get().uri("/ping").retrieve().bodyToMono(String.class), 0);
            return "pong".equalsIgnoreCase(pong == null ? "" : pong.trim())
                    ? ProviderHealth.online("API respondendo")
                    : ProviderHealth.offline("Resposta inesperada do /ping");
        } catch (ProviderException e) {
            return ProviderHealth.offline(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode get(java.util.function.Function<UriBuilder, URI> uri) {
        apiLimiter.acquire();
        JsonNode body = http.execute(CODE, api.get().uri(uri).retrieve().bodyToMono(JsonNode.class));
        if (body == null || !"ok".equals(Json.text(body, "result"))) {
            throw new ProviderException(CODE, "Resposta inválida do MangaDex.");
        }
        return body;
    }

    private AtHome atHome(String chapterId) {
        AtHome cached = atHomeCache.getIfPresent(chapterId);
        if (cached != null) {
            return cached;
        }
        atHomeLimiter.acquire();
        JsonNode body = http.execute(CODE, api.get().uri("/at-home/server/{id}", chapterId)
                .retrieve().bodyToMono(JsonNode.class));
        if (body == null || !"ok".equals(Json.text(body, "result"))) {
            throw new ProviderException(CODE, "Não foi possível obter o servidor de páginas do capítulo.");
        }
        JsonNode ch = body.path("chapter");
        List<String> files = Json.array(ch, "data").stream().map(JsonNode::asString).toList();
        if (files.isEmpty()) {
            throw new ProviderException(CODE, "Capítulo sem páginas disponíveis no MangaDex (pode ser hospedado externamente).");
        }
        AtHome atHome = new AtHome(Json.text(body, "baseUrl"), Json.text(ch, "hash"), files);
        atHomeCache.put(chapterId, atHome);
        return atHome;
    }

    /** Reporte exigido pela rede MangaDex@Home (não se aplica a hosts mangadex.org). */
    private void report(URI uri, String host, boolean success, long bytes, long startNanos, boolean cached) {
        if (host.endsWith("mangadex.org") || config.reportUrl() == null || config.reportUrl().isBlank()) {
            return;
        }
        long duration = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("url", uri.toString());
        payload.put("success", success);
        payload.put("bytes", bytes);
        payload.put("duration", duration);
        payload.put("cached", cached);
        CompletableFuture.runAsync(() -> {
            try {
                raw.post().uri(config.reportUrl()).contentType(MediaType.APPLICATION_JSON).bodyValue(payload)
                        .retrieve().toBodilessEntity().block(Duration.ofSeconds(10));
            } catch (RuntimeException e) {
                log.debug("Falha ao reportar ao MangaDex@Home: {}", e.getMessage());
            }
        });
    }

    RemoteTitle toRemoteTitle(JsonNode m) {
        JsonNode a = m.path("attributes");
        String id = Json.text(m, "id");
        return new RemoteTitle(CODE, displayName(), id, mainTitle(a), altTitles(a),
                localized(a.path("description")), coverUrl(m),
                ContentType.fromOriginalLanguage(Json.text(a, "originalLanguage")), languages(a),
                Json.integer(a, "year"), Json.text(a, "status"), "https://mangadex.org/title/" + id, null);
    }

    RemoteChapter toChapter(JsonNode c) {
        JsonNode a = c.path("attributes");
        String scanlator = null;
        for (JsonNode rel : Json.array(c, "relationships")) {
            if ("scanlation_group".equals(Json.text(rel, "type"))) {
                scanlator = Json.text(rel.path("attributes"), "name");
            }
        }
        String externalUrl = Json.text(a, "externalUrl");
        boolean unavailable = Json.bool(a, "isUnavailable");
        Integer pages = Json.integer(a, "pages");
        boolean readable = externalUrl == null && !unavailable && (pages == null || pages > 0);
        String number = Json.text(a, "chapter");
        return new RemoteChapter(Json.text(c, "id"), parseNumber(number), number, Json.text(a, "volume"),
                Json.text(a, "title"), Json.text(a, "translatedLanguage"), scanlator,
                parseInstant(Json.text(a, "publishAt")), pages, readable, readable, externalUrl);
    }

    private String coverUrl(JsonNode m) {
        for (JsonNode rel : Json.array(m, "relationships")) {
            if ("cover_art".equals(Json.text(rel, "type"))) {
                String file = Json.text(rel.path("attributes"), "fileName");
                if (file != null) {
                    return config.uploadsUrl() + "/covers/" + Json.text(m, "id") + "/" + file + ".512.jpg";
                }
            }
        }
        return null;
    }

    private static String mainTitle(JsonNode attributes) {
        String t = localized(attributes.path("title"));
        return t == null ? "(sem título)" : t;
    }

    private static List<String> altTitles(JsonNode attributes) {
        List<String> out = new ArrayList<>();
        JsonNode alt = attributes.path("altTitles");
        // Títulos em português primeiro
        for (String lang : List.of("pt-br", "pt", "en", "ja-ro")) {
            alt.forEach(node -> {
                String v = Json.text(node, lang);
                if (v != null && !out.contains(v)) {
                    out.add(v);
                }
            });
        }
        return out;
    }

    /** Escolhe o texto localizado preferindo pt-br, depois en, depois o primeiro disponível. */
    static String localized(JsonNode map) {
        if (map == null || !map.isObject() || map.isEmpty()) {
            return null;
        }
        for (String lang : List.of("pt-br", "pt", "en", "ja-ro")) {
            String v = Json.text(map, lang);
            if (v != null) {
                return v;
            }
        }
        for (Map.Entry<String, JsonNode> e : map.properties()) {
            if (e.getValue().isString() && !e.getValue().asString().isBlank()) {
                return e.getValue().asString();
            }
        }
        return null;
    }

    private static Set<String> languages(JsonNode attributes) {
        Set<String> out = new LinkedHashSet<>();
        Json.array(attributes, "availableTranslatedLanguages").forEach(n -> {
            if (n.isString()) {
                out.add(n.asString().toLowerCase());
            }
        });
        return out;
    }

    private static List<String> originalLanguagesFor(Set<ContentType> types) {
        if (types == null || types.isEmpty()) {
            return List.of();
        }
        List<String> langs = new ArrayList<>();
        if (types.contains(ContentType.MANGA)) {
            langs.add("ja");
        }
        if (types.contains(ContentType.MANHWA)) {
            langs.add("ko");
        }
        if (types.contains(ContentType.MANHUA)) {
            langs.addAll(List.of("zh", "zh-hk"));
        }
        if (types.contains(ContentType.COMIC)) {
            langs.add("en");
        }
        return langs;
    }

    static BigDecimal parseNumber(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(number.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant parseInstant(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void requireUuid(String id) {
        if (id == null || !id.matches("[0-9a-fA-F-]{36}")) {
            throw new ProviderException(CODE, "Identificador MangaDex inválido.");
        }
    }


    private record AtHome(String baseUrl, String hash, List<String> files) {
    }
}
