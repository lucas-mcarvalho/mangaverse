package com.mangaverse.provider;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mangaverse.common.ApiException;
import com.mangaverse.common.Json;
import com.mangaverse.config.CacheConfig;
import com.mangaverse.content.Content;
import com.mangaverse.content.ContentRepository;
import com.mangaverse.content.ContentSource;
import com.mangaverse.content.ContentSourceRepository;
import com.mangaverse.provider.comicvine.ComicVineProvider;
import com.mangaverse.provider.mangadex.MangaDexProvider;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.provider.suwayomi.SuwayomiClient;
import com.mangaverse.provider.suwayomi.SuwayomiSourceProvider;

import tools.jackson.databind.JsonNode;

/** Gerencia provedores, fontes do Suwayomi, extensões e repositórios de extensões. */
@Service
public class SourceManagementService {

    private static final Logger log = LoggerFactory.getLogger(SourceManagementService.class);

    private final ProviderRepository providers;
    private final ContentSourceRepository contentSources;
    private final ContentRepository contents;
    private final SuwayomiClient suwayomi;
    private final MangaDexProvider mangaDex;
    private final ComicVineProvider comicVine;
    private final ProviderService providerService;
    private final CacheManager cacheManager;
    /** Extensões de HQs ocidentais: entram como tipo HQ por padrão (o usuário pode mudar). */
    static final Set<String> COMIC_EXTENSIONS = Set.of("hqnow", "readcomicsonline", "xoxocomics", "comichubfree",
            "digitalcomicmuseum", "comicskingdom");
    static final Set<String> ALLOWED_TYPES = Set.of("AUTO", "MANGA", "MANHWA", "MANHUA", "COMIC");

    private final Cache<String, List<ExtensionView>> extensionCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(3)).build();

    public SourceManagementService(ProviderRepository providers, ContentSourceRepository contentSources, ContentRepository contents,
                                   SuwayomiClient suwayomi, MangaDexProvider mangaDex, ComicVineProvider comicVine,
                                   ProviderService providerService, CacheManager cacheManager) {
        this.providers = providers;
        this.contentSources = contentSources;
        this.contents = contents;
        this.suwayomi = suwayomi;
        this.mangaDex = mangaDex;
        this.comicVine = comicVine;
        this.providerService = providerService;
        this.cacheManager = cacheManager;
    }

    public record ExtensionView(String pkgName, String name, String lang, String versionName, String iconUrl,
                                boolean installed, boolean hasUpdate, boolean obsolete, String contentWarning,
                                String storeIndexUrl) {
    }

    public record StoreView(String name, String indexUrl, String badgeLabel, boolean legacy) {
    }

    public record SuwayomiStatus(boolean enabled, boolean online, String baseUrl, String version, String message,
                                 String defaultStore) {
    }

    /** Na inicialização, sincroniza as fontes já instaladas no Suwayomi (sem instalar nada). */
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (!suwayomi.enabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                syncSuwayomiSources();
            } catch (RuntimeException e) {
                log.warn("Suwayomi indisponível na inicialização; fontes não sincronizadas: {}", e.getMessage());
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Provider> listProviders() {
        return providers.findAllByOrderByTypeAscPriorityAscNameAsc();
    }

    @Transactional
    public Provider update(java.util.UUID id, Boolean enabled, Integer priority) {
        return update(id, enabled, priority, null);
    }

    /**
     * Atualiza a fonte. Ao definir um tipo (ex.: HQ), os títulos importados somente desta fonte
     * também passam a usar esse tipo (afeta rótulos "Edição" e a pasta HQs/ dos próximos downloads).
     */
    @Transactional
    public Provider update(java.util.UUID id, Boolean enabled, Integer priority, String defaultContentType) {
        Provider p = providers.findById(id).orElseThrow(() -> ApiException.notFound("Provedor não encontrado."));
        if (defaultContentType != null) {
            if (!ALLOWED_TYPES.contains(defaultContentType)) {
                throw ApiException.badRequest("Tipo inválido: " + defaultContentType);
            }
            if (p.getType() != ProviderType.SUWAYOMI_SOURCE) {
                throw ApiException.badRequest("O tipo padrão só pode ser definido para fontes do Suwayomi.");
            }
            p.setDefaultContentType(defaultContentType);
            applyTypeToContents(p);
            clearMetadataCaches();
        }
        if (enabled != null) {
            p.setEnabled(enabled);
        }
        if (priority != null) {
            p.setPriority(Math.max(0, Math.min(1000, priority)));
        }
        return p;
    }

    /** Verifica a disponibilidade dos provedores principais e grava o status. */
    public Map<String, ProviderHealth> checkHealth() {
        Map<String, ProviderHealth> result = new java.util.LinkedHashMap<>();
        result.put(MangaDexProvider.CODE, mangaDex.health());
        result.put(ComicVineProvider.CODE, comicVine.health());
        SuwayomiStatus status = suwayomiStatus();
        result.put("suwayomi", status.online() ? ProviderHealth.online(status.message()) : ProviderHealth.offline(status.message()));
        result.put("local", ProviderHealth.online("Arquivos locais"));
        result.forEach(providerService::recordStatus);
        return result;
    }

    /** Aplica o tipo da fonte aos títulos importados somente dela (títulos locais ou com outras fontes ficam como estão). */
    void applyTypeToContents(Provider p) {
        p.forcedType().ifPresent(type -> {
            int changed = 0;
            for (ContentSource cs : contentSources.findByProviderId(p.getId())) {
                Content content = cs.getContent();
                boolean onlyThisProvider = contentSources.findByContentId(content.getId()).stream()
                        .allMatch(other -> other.getProvider().getId().equals(p.getId()));
                if (onlyThisProvider && content.getLocalPath() == null && content.getType() != type) {
                    content.setType(type);
                    contents.save(content);
                    changed++;
                }
            }
            log.info("Fonte {} definida como {}; {} título(s) atualizado(s)", p.getName(), type, changed);
        });
    }

    private void clearMetadataCaches() {
        for (String cache : List.of(CacheConfig.SEARCH, CacheConfig.DETAILS, CacheConfig.DISCOVER)) {
            var c = cacheManager.getCache(cache);
            if (c != null) {
                c.clear();
            }
        }
    }

    public SuwayomiStatus suwayomiStatus() {
        if (!suwayomi.enabled()) {
            return new SuwayomiStatus(false, false, suwayomi.baseUrl(), null, "Integração desabilitada (SUWAYOMI_ENABLED=false).", suwayomi.defaultExtensionStore());
        }
        try {
            JsonNode about = suwayomi.execute(SuwayomiClient.Q_ABOUT, Map.of()).path("aboutServer");
            String version = Json.text(about, "version");
            return new SuwayomiStatus(true, true, suwayomi.baseUrl(), version, "Conectado ao Suwayomi " + version, suwayomi.defaultExtensionStore());
        } catch (RuntimeException e) {
            return new SuwayomiStatus(true, false, suwayomi.baseUrl(), null, e.getMessage(), suwayomi.defaultExtensionStore());
        }
    }

    /** Sincroniza as fontes instaladas no Suwayomi com a tabela de provedores. Fontes pt-BR ficam habilitadas por padrão. */
    @Transactional
    public List<Provider> syncSuwayomiSources() {
        JsonNode data = suwayomi.execute(SuwayomiClient.Q_SOURCES, Map.of());
        Set<String> seen = new HashSet<>();
        for (JsonNode s : Json.array(data.path("sources"), "nodes")) {
            String id = Json.text(s, "id");
            if (id == null || "0".equals(id)) {
                continue; // "Local source" do próprio Suwayomi
            }
            String code = SuwayomiSourceProvider.PREFIX + id;
            seen.add(code);
            String lang = normalizeLang(Json.text(s, "lang"));
            Provider p = providers.findByCode(code).orElseGet(() -> {
                Provider created = new Provider(code, Json.text(s, "name"), ProviderType.SUWAYOMI_SOURCE);
                boolean ptBr = "pt-br".equals(lang);
                created.setEnabled(ptBr);
                created.setPriority(ptBr ? 20 : "en".equals(lang) ? 60 : 80);
                created.setSupportsDownload(true);
                return created;
            });
            p.setName(Json.text(s, "name"));
            p.setLanguage(lang);
            p.setExternalRef(id);
            p.setSupportsSearch(true);
            String icon = Json.text(s, "iconUrl");
            p.setIconUrl(icon != null && icon.startsWith("/") ? SuwayomiSourceProvider.COVER_PREFIX + icon : icon);
            if (p.getDefaultContentType() == null) {
                String pkg = Json.text(s.path("extension"), "pkgName");
                String shortPkg = pkg == null ? "" : pkg.substring(pkg.lastIndexOf('.') + 1);
                p.setDefaultContentType(COMIC_EXTENSIONS.contains(shortPkg) ? "COMIC" : "AUTO");
                if (p.getId() != null) {
                    applyTypeToContents(p); // fonte já existente marcada como HQ pela primeira vez
                }
            }
            p.setLastStatus("ONLINE");
            p.setLastError(null);
            providers.save(p);
        }
        for (Provider p : providers.findByTypeOrderByPriorityAscNameAsc(ProviderType.SUWAYOMI_SOURCE)) {
            if (!seen.contains(p.getCode())) {
                if (contentSources.existsByProviderId(p.getId())) {
                    p.setEnabled(false);
                    p.setSupportsSearch(false);
                    p.setLastStatus("OFFLINE");
                    p.setLastError("Fonte não está mais instalada no Suwayomi.");
                    providers.save(p); // também chamado fora de transação (startup / changeExtension)
                } else {
                    providers.delete(p);
                }
            }
        }
        log.info("Fontes do Suwayomi sincronizadas: {}", seen.size());
        return providers.findByTypeOrderByPriorityAscNameAsc(ProviderType.SUWAYOMI_SOURCE);
    }

    public List<ExtensionView> extensions(String lang, Boolean installed, String query) {
        List<ExtensionView> all = extensionCache.get("all", k -> loadExtensions());
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT).trim();
        String l = lang == null || lang.isBlank() ? null : normalizeLang(lang);
        return all.stream()
                .filter(e -> l == null || l.equals(normalizeLang(e.lang())))
                .filter(e -> installed == null || e.installed() == installed)
                .filter(e -> q.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(q) || e.pkgName().contains(q))
                .sorted(Comparator.comparing((ExtensionView e) -> !e.installed())
                        .thenComparing(e -> !"pt-br".equals(normalizeLang(e.lang())))
                        .thenComparing(e -> e.name().toLowerCase(Locale.ROOT)))
                .toList();
    }

    public List<ExtensionView> refreshExtensions() {
        suwayomi.execute(SuwayomiClient.M_FETCH_EXTENSIONS, Map.of());
        extensionCache.invalidateAll();
        return extensions(null, null, null);
    }

    public void changeExtension(String pkgName, String action) {
        if (pkgName == null || !pkgName.matches("[a-zA-Z0-9_.]{3,200}")) {
            throw ApiException.badRequest("Pacote inválido.");
        }
        Map<String, Object> patch = switch (action) {
            case "install" -> Map.of("install", true);
            case "uninstall" -> Map.of("uninstall", true);
            case "update" -> Map.of("update", true);
            default -> throw ApiException.badRequest("Ação inválida: " + action);
        };
        JsonNode result = suwayomi.execute(SuwayomiClient.M_UPDATE_EXTENSION, Map.of("id", pkgName, "patch", patch));
        extensionCache.invalidateAll();
        JsonNode extension = result.path("updateExtension").path("extension");
        if (extension.isMissingNode() || extension.isNull()) {
            throw ApiException.notFound("Extensão não encontrada no Suwayomi. Atualize a lista de extensões e tente novamente.");
        }
        syncSuwayomiSources();
        if ("install".equals(action)) {
            enableSourcesOf(pkgName);
        }
    }

    /** Fontes de uma extensão instalada pelo usuário entram habilitadas na pesquisa, em qualquer idioma. */
    void enableSourcesOf(String pkgName) {
        JsonNode data = suwayomi.execute(SuwayomiClient.Q_SOURCES, Map.of());
        for (JsonNode src : Json.array(data.path("sources"), "nodes")) {
            if (pkgName.equals(Json.text(src.path("extension"), "pkgName"))) {
                providers.findByCode(SuwayomiSourceProvider.PREFIX + Json.text(src, "id")).ifPresent(p -> {
                    p.setEnabled(true);
                    providers.save(p);
                    log.info("Fonte {} ({}) habilitada após instalação", p.getName(), p.getLanguage());
                });
            }
        }
    }

    public List<StoreView> stores() {
        JsonNode data = suwayomi.execute(SuwayomiClient.Q_STORES, Map.of());
        List<StoreView> out = new ArrayList<>();
        for (JsonNode s : Json.array(data.path("extensionStores"), "nodes")) {
            out.add(new StoreView(Json.text(s, "name"), Json.text(s, "indexUrl"), Json.text(s, "badgeLabel"), Json.bool(s, "isLegacy")));
        }
        return out;
    }

    public List<StoreView> addStore(String indexUrl) {
        validateStoreUrl(indexUrl);
        suwayomi.execute(SuwayomiClient.M_ADD_STORE, Map.of("url", indexUrl));
        suwayomi.execute(SuwayomiClient.M_FETCH_EXTENSIONS, Map.of());
        extensionCache.invalidateAll();
        return stores();
    }

    public List<StoreView> removeStore(String indexUrl) {
        validateStoreUrl(indexUrl);
        suwayomi.execute(SuwayomiClient.M_REMOVE_STORE, Map.of("url", indexUrl));
        extensionCache.invalidateAll();
        return stores();
    }

    private List<ExtensionView> loadExtensions() {
        JsonNode data = suwayomi.execute(SuwayomiClient.Q_EXTENSIONS, Map.of());
        if (Json.array(data.path("extensions"), "nodes").isEmpty()) {
            // O Suwayomi só popula a lista após consultar os repositórios
            suwayomi.execute(SuwayomiClient.M_FETCH_EXTENSIONS, Map.of());
            data = suwayomi.execute(SuwayomiClient.Q_EXTENSIONS, Map.of());
        }
        List<ExtensionView> out = new ArrayList<>();
        for (JsonNode e : Json.array(data.path("extensions"), "nodes")) {
            String icon = Json.text(e, "iconUrl");
            out.add(new ExtensionView(Json.text(e, "pkgName"), Json.text(e, "name"), Json.text(e, "lang"),
                    Json.text(e, "versionName"),
                    icon != null && icon.startsWith("/") ? SuwayomiSourceProvider.COVER_PREFIX + icon : icon,
                    Json.bool(e, "isInstalled"), Json.bool(e, "hasUpdate"), Json.bool(e, "isObsolete"),
                    Json.text(e, "contentWarning"), Json.text(e, "storeIndexUrl")));
        }
        return out;
    }

    private static void validateStoreUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw ApiException.badRequest("O repositório deve ser uma URL https válida.");
            }
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("URL de repositório inválida.");
        }
    }

    public static String normalizeLang(String lang) {
        return lang == null ? null : lang.toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
