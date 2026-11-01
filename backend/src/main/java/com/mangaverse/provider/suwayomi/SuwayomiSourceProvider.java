package com.mangaverse.provider.suwayomi;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mangaverse.common.Json;
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
 * Adaptador de uma fonte (extensão) instalada no Suwayomi. O código do provedor é {@code suwayomi:<sourceId>}
 * e os identificadores externos são os IDs internos de mangá/capítulo do Suwayomi.
 */
public class SuwayomiSourceProvider implements ContentProvider {

    public static final String PREFIX = "suwayomi:";
    public static final String COVER_PREFIX = "suwayomi:";
    private static final Pattern VOLUME = Pattern.compile("(?i)\\b(?:vol(?:ume)?\\.?)\\s*(\\d+(?:\\.\\d+)?)");

    private final SuwayomiClient client;
    private final String sourceId;
    private final String name;
    private final String lang;
    private final ContentType forcedType;

    public SuwayomiSourceProvider(SuwayomiClient client, String sourceId, String name, String lang) {
        this(client, sourceId, name, lang, null);
    }

    /** @param forcedType tipo definido para a fonte (ex.: HQ); {@code null} deduz pelos gêneros */
    public SuwayomiSourceProvider(SuwayomiClient client, String sourceId, String name, String lang, ContentType forcedType) {
        this.client = client;
        this.sourceId = sourceId;
        this.name = name;
        this.lang = lang == null ? null : lang.toLowerCase(Locale.ROOT);
        this.forcedType = forcedType;
    }

    public static boolean handles(String code) {
        return code != null && code.startsWith(PREFIX) && code.substring(PREFIX.length()).matches("-?\\d{1,25}");
    }

    @Override
    public String code() {
        return PREFIX + sourceId;
    }

    @Override
    public String displayName() {
        return name + (lang != null ? " (" + lang + ")" : "") + " · Suwayomi";
    }

    @Override
    public ProviderCapabilities capabilities() {
        return new ProviderCapabilities(true, true, true, true, false, true);
    }

    @Override
    public List<RemoteTitle> search(SearchQuery query) {
        return fetchSource("SEARCH", query.text(), 1).stream()
                .filter(t -> query.types().isEmpty() || query.types().contains(t.type()))
                .limit(Math.max(1, query.limit()))
                .toList();
    }

    @Override
    public List<RemoteTitle> discover(DiscoverKind kind, Set<String> languages, int page) {
        return fetchSource(kind == DiscoverKind.POPULAR ? "POPULAR" : "LATEST", null, Math.max(1, page));
    }

    private List<RemoteTitle> fetchSource(String type, String query, int page) {
        JsonNode data = client.execute(SuwayomiClient.M_SEARCH,
                Map.of("source", sourceId, "query", query == null ? "" : query, "page", page, "type", type));
        List<RemoteTitle> out = new ArrayList<>();
        for (JsonNode m : Json.array(data.path("fetchSourceManga"), "mangas")) {
            out.add(new RemoteTitle(code(), displayName(), Json.text(m, "id"), Json.text(m, "title"), List.of(),
                    Json.text(m, "description"), cover(m), typeOf(m), lang == null ? Set.of() : Set.of(lang),
                    null, status(Json.text(m, "status")), Json.text(m, "realUrl"), null));
        }
        return out;
    }

    @Override
    public RemoteTitleDetails getDetails(String externalId) {
        JsonNode m = fetchMangaAndChapters(externalId).path("manga");
        if (m.isMissingNode() || m.isNull()) {
            throw new ProviderException(code(), "Título não encontrado no Suwayomi.");
        }
        return new RemoteTitleDetails(code(), externalId, Json.text(m, "title"), List.of(), Json.text(m, "description"),
                cover(m), typeOf(m), splitPeople(Json.text(m, "author")), splitPeople(Json.text(m, "artist")), null,
                strings(m.path("genre")), List.of(), lang == null ? Set.of() : Set.of(lang), null,
                status(Json.text(m, "status")), null, Json.text(m, "realUrl"));
    }

    @Override
    public List<RemoteChapter> getChapters(String externalId, Set<String> languages) {
        JsonNode result = fetchMangaAndChapters(externalId);
        List<RemoteChapter> out = new ArrayList<>();
        for (JsonNode c : Json.array(result, "chapters")) {
            double number = c.path("chapterNumber").asDouble(-1);
            String chapterName = Json.text(c, "name");
            String uploadDate = Json.text(c, "uploadDate");
            Integer pageCount = Json.integer(c, "pageCount");
            out.add(new RemoteChapter(Json.text(c, "id"),
                    number >= 0 ? BigDecimal.valueOf(number).stripTrailingZeros() : null,
                    number >= 0 ? BigDecimal.valueOf(number).stripTrailingZeros().toPlainString() : null,
                    volumeOf(chapterName), chapterName, lang, Json.text(c, "scanlator"),
                    parseMillis(uploadDate), pageCount != null && pageCount > 0 ? pageCount : null,
                    true, true, null));
        }
        return out;
    }

    @Override
    public Set<String> getAvailableLanguages(String externalId) {
        return lang == null ? Set.of() : Set.of(lang);
    }

    @Override
    public List<PageRef> getPages(String chapterExternalId) {
        int id = parseId(chapterExternalId);
        JsonNode data = client.execute(SuwayomiClient.M_FETCH_PAGES, Map.of("id", id));
        List<PageRef> pages = new ArrayList<>();
        List<JsonNode> list = Json.array(data.path("fetchChapterPages"), "pages");
        for (int i = 0; i < list.size(); i++) {
            pages.add(new PageRef(i, list.get(i).asString()));
        }
        if (pages.isEmpty()) {
            throw new ProviderException(code(), "A fonte não retornou páginas para este capítulo.");
        }
        return pages;
    }

    @Override
    public FetchedImage fetchPage(PageRef page) {
        return client.fetchBinary(page.url());
    }

    @Override
    public ProviderHealth health() {
        try {
            JsonNode about = client.execute(SuwayomiClient.Q_ABOUT, Map.of()).path("aboutServer");
            return ProviderHealth.online("Suwayomi " + Json.text(about, "version"));
        } catch (ProviderException e) {
            return ProviderHealth.offline(e.getMessage());
        }
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode fetchMangaAndChapters(String externalId) {
        // "No chapters found" chega como erro com dados parciais: aceitamos a resposta parcial.
        return client.execute(SuwayomiClient.M_FETCH_MANGA_AND_CHAPTERS, Map.of("id", parseId(externalId)), true)
                .path("fetchMangaAndChapters");
    }

    private int parseId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            throw new ProviderException(code(), "Identificador Suwayomi inválido.");
        }
    }

    private static String cover(JsonNode m) {
        String thumb = Json.text(m, "thumbnailUrl");
        if (thumb == null) {
            return null;
        }
        return thumb.startsWith("/") ? COVER_PREFIX + thumb : thumb;
    }

    private ContentType typeOf(JsonNode m) {
        return forcedType != null ? forcedType : guessType(m);
    }

    static ContentType guessType(JsonNode m) {
        String genres = String.join(" ", strings(m.path("genre"))).toLowerCase(Locale.ROOT);
        if (genres.contains("manhwa") || genres.contains("coreano") || genres.contains("korean") || genres.contains("webtoon")) {
            return ContentType.MANHWA;
        }
        if (genres.contains("manhua") || genres.contains("chinês") || genres.contains("chinese")) {
            return ContentType.MANHUA;
        }
        if (genres.contains("comic") && !genres.contains("web comic") || genres.contains("hq") || genres.contains("quadrinhos")) {
            return ContentType.COMIC;
        }
        return ContentType.MANGA;
    }

    static String volumeOf(String chapterName) {
        if (chapterName == null) {
            return null;
        }
        Matcher m = VOLUME.matcher(chapterName);
        return m.find() ? m.group(1) : null;
    }

    private static String status(String suwayomiStatus) {
        if (suwayomiStatus == null) {
            return null;
        }
        return switch (suwayomiStatus) {
            case "ONGOING" -> "ongoing";
            case "COMPLETED", "PUBLISHING_FINISHED" -> "completed";
            case "ON_HIATUS" -> "hiatus";
            case "CANCELLED" -> "cancelled";
            default -> null;
        };
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(n -> {
                if (n.isString() && !n.asString().isBlank()) {
                    out.add(n.asString());
                }
            });
        }
        return out;
    }

    private static List<String> splitPeople(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return List.of(value.split("\\s*,\\s*"));
    }

    private static Instant parseMillis(String millis) {
        if (millis == null) {
            return null;
        }
        try {
            long v = Long.parseLong(millis);
            return v > 0 ? Instant.ofEpochMilli(v) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
