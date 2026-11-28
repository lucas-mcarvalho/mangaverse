package com.mangaverse.catalog;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mangaverse.common.ApiException;
import com.mangaverse.provider.Provider;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.DiscoverKind;
import com.mangaverse.provider.spi.Genre;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.settings.SettingsService;

/**
 * Catálogo do Explorar: fontes com populares/lançamentos, categorias de cada fonte e listagem por categoria.
 * Fontes +18 ficam de fora com o modo +18 desligado (o {@link ProviderRegistry#catalog()} já filtra).
 */
@Service
public class CatalogService {

    private final ProviderRegistry registry;
    private final ProviderService providerService;
    private final SettingsService settings;
    private final Cache<String, List<Genre>> genres = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(30)).maximumSize(300).build();
    private final Cache<String, List<RemoteTitle>> byGenre = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(15)).maximumSize(300).build();

    public CatalogService(ProviderRegistry registry, ProviderService providerService, SettingsService settings) {
        this.registry = registry;
        this.providerService = providerService;
        this.settings = settings;
    }

    public enum Kind { POPULAR, LATEST, GENRE }

    public record CatalogSource(String code, String name, String language, String iconUrl, boolean supportsLatest, String type, boolean adult) {
    }

    public record Listing(String provider, Kind kind, String genre, int page, List<RemoteTitle> items, boolean hasNextPage) {
    }

    public List<CatalogSource> sources() {
        return registry.catalog().stream().map(e -> {
            Provider p = e.provider();
            return new CatalogSource(p.getCode(), p.getName(), p.getLanguage(), p.getIconUrl(), p.isSupportsLatest(), p.getType().name(), p.isAdult());
        }).toList();
    }

    private ProviderRegistry.CatalogEntry require(String code) {
        return registry.catalog().stream().filter(e -> e.provider().getCode().equals(code)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Fonte indisponível no catálogo: " + code));
    }

    public List<Genre> genres(String code) {
        ProviderRegistry.CatalogEntry entry = require(code);
        List<Genre> cached = genres.getIfPresent(code);
        if (cached != null) {
            return cached;
        }
        List<Genre> list;
        try {
            list = entry.impl().genres();
        } catch (ProviderException e) {
            list = List.of(); // fonte fora do ar: sem categorias desta vez (não guarda)
            return list;
        }
        genres.put(code, list);
        return list;
    }

    public Listing listing(String code, Kind kind, String genre, int page) {
        ProviderRegistry.CatalogEntry entry = require(code);
        int p = Math.max(1, page);
        List<RemoteTitle> items = switch (kind) {
            case POPULAR -> providerService.discover(entry.impl(), DiscoverKind.POPULAR, languages(), p);
            case LATEST -> {
                if (!entry.provider().isSupportsLatest()) {
                    throw ApiException.badRequest("Esta fonte não tem lista de lançamentos.");
                }
                yield providerService.discover(entry.impl(), DiscoverKind.LATEST, languages(), p);
            }
            case GENRE -> {
                if (genre == null || genre.isBlank()) {
                    throw ApiException.badRequest("Informe a categoria.");
                }
                yield byGenre.get(code + "|" + genre + "|" + p, k -> entry.impl().byGenre(genre, p));
            }
        };
        return new Listing(code, kind, genre, p, items, !items.isEmpty());
    }

    private Set<String> languages() {
        return Set.of(settings.preferredLanguage());
    }
}
