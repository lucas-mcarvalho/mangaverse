package com.mangaverse.provider.suwayomi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;

import com.mangaverse.common.Json;
import com.mangaverse.common.RateLimiter;
import com.mangaverse.config.AppProperties;
import com.mangaverse.config.HttpSupport;
import com.mangaverse.provider.spi.FetchedImage;
import com.mangaverse.provider.spi.ProviderException;

import tools.jackson.databind.JsonNode;

/**
 * Cliente da API GraphQL do Suwayomi Server.
 * As operações abaixo foram conferidas por introspecção do schema da versão v2.4.2366
 * (ver docs/suwayomi-schema.md). Execução de extensões fica inteiramente a cargo do Suwayomi.
 */
public class SuwayomiClient {

    public static final String CODE = "suwayomi";

    public static final String Q_ABOUT = "query { aboutServer { name version buildType } }";

    public static final String Q_SOURCES = """
            query { sources { nodes { id name lang displayName iconUrl isConfigurable supportsLatest contentWarning
              extension { pkgName name } } } }""";

    public static final String Q_EXTENSIONS = """
            query { extensions { nodes { pkgName name lang versionName iconUrl isInstalled hasUpdate isObsolete
              contentWarning storeIndexUrl } } }""";

    public static final String Q_STORES = "query { extensionStores { nodes { name indexUrl badgeLabel isLegacy } } }";

    public static final String M_FETCH_EXTENSIONS = "mutation { fetchExtensions(input: {}) { extensions { pkgName } } }";

    public static final String M_UPDATE_EXTENSION = """
            mutation($id: String!, $patch: UpdateExtensionPatchInput!) {
              updateExtension(input: {id: $id, patch: $patch}) { extension { pkgName isInstalled hasUpdate } } }""";

    public static final String M_ADD_STORE = """
            mutation($url: String!) { addExtensionStore(input: {indexUrl: $url}) { extensionStore { name indexUrl } } }""";

    public static final String M_REMOVE_STORE = """
            mutation($url: String!) { removeExtensionStore(input: {indexUrl: $url}) { extensionStore { indexUrl } } }""";

    public static final String MANGA_FIELDS = "id title thumbnailUrl url realUrl status description author artist genre sourceId";

    public static final String M_SEARCH = """
            mutation($source: LongString!, $query: String, $page: Int!, $type: FetchSourceMangaType!) {
              fetchSourceManga(input: {source: $source, type: $type, query: $query, page: $page}) {
                hasNextPage mangas { %s } } }""".formatted(MANGA_FIELDS);

    public static final String M_FETCH_MANGA_AND_CHAPTERS = """
            mutation($id: Int!) { fetchMangaAndChapters(input: {id: $id, fetchManga: true, fetchChapters: true}) {
              manga { %s source { id name lang } }
              chapters { id name chapterNumber scanlator uploadDate pageCount sourceOrder realUrl } } }""".formatted(MANGA_FIELDS);

    public static final String Q_MANGA = "query($id: Int!) { manga(id: $id) { %s source { id name lang } } }".formatted(MANGA_FIELDS);

    public static final String M_FETCH_PAGES = "mutation($id: Int!) { fetchChapterPages(input: {chapterId: $id}) { pages } }";

    private final AppProperties.Suwayomi config;
    private final HttpSupport http;
    private final WebClient client;
    private final RateLimiter limiter;

    public SuwayomiClient(AppProperties.Suwayomi config, HttpSupport http) {
        this(config, http, http.client(config.baseUrl()));
    }

    SuwayomiClient(AppProperties.Suwayomi config, HttpSupport http, WebClient client) {
        this.config = config;
        this.http = http;
        this.client = client;
        this.limiter = new RateLimiter(config.requestsPerSecond());
    }

    public boolean enabled() {
        return config.enabled();
    }

    public String baseUrl() {
        return config.baseUrl();
    }

    public String defaultExtensionStore() {
        return config.defaultExtensionStore();
    }

    public int maxSourcesPerSearch() {
        return config.maxSourcesPerSearch();
    }

    /** Executa uma operação GraphQL. Respostas parciais são aceitas quando {@code allowPartial} é verdadeiro. */
    public JsonNode execute(String query, Map<String, Object> variables, boolean allowPartial) {
        if (!config.enabled()) {
            throw new ProviderException(CODE, "Integração com Suwayomi desabilitada.");
        }
        limiter.acquire();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        payload.put("variables", variables == null ? Map.of() : variables);
        JsonNode body = http.execute(CODE, client.post().uri("/api/graphql")
                .headers(this::authorize)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class), 1);
        if (body == null) {
            throw new ProviderException(CODE, "Resposta vazia do Suwayomi.");
        }
        JsonNode errors = body.path("errors");
        JsonNode data = body.path("data");
        if (errors.isArray() && !errors.isEmpty()) {
            List<String> messages = new ArrayList<>();
            errors.forEach(e -> messages.add(Json.text(e, "message")));
            if (!allowPartial || data.isMissingNode() || data.isNull()) {
                throw new ProviderException(CODE, "Erro do Suwayomi: " + String.join("; ", messages));
            }
        }
        return data;
    }

    public JsonNode execute(String query, Map<String, Object> variables) {
        return execute(query, variables, false);
    }

    /** Obtém um recurso binário (página, capa, ícone) servido pelo Suwayomi. */
    public FetchedImage fetchBinary(String path) {
        if (path == null || !path.startsWith("/api/v1/") || path.contains("..") || path.contains("://")) {
            throw new ProviderException(CODE, "Caminho de recurso inválido.");
        }
        limiter.acquire();
        ResponseEntity<byte[]> response = http.execute(CODE, client.get().uri(config.baseUrl() + path)
                .headers(this::authorize)
                .accept(MediaType.ALL).retrieve().toEntity(byte[].class), 1);
        String type = response.getHeaders().getContentType() == null ? "image/jpeg" : response.getHeaders().getContentType().toString();
        return new FetchedImage(response.getBody() == null ? new byte[0] : response.getBody(), type);
    }

    private void authorize(HttpHeaders headers) {
        if (config.username() != null && !config.username().isBlank()) {
            headers.setBasicAuth(config.username(), config.password() == null ? "" : config.password(), StandardCharsets.UTF_8);
        }
    }

}
