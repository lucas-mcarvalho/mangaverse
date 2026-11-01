package com.mangaverse.provider;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mangaverse.config.CacheConfig;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.DiscoverKind;
import com.mangaverse.provider.spi.PageRef;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.provider.spi.RemoteTitleDetails;
import com.mangaverse.provider.spi.SearchQuery;

/** Fachada com cache sobre os provedores. */
@Service
public class ProviderService {

    private final ProviderRegistry registry;
    private final ProviderRepository repository;

    public ProviderService(ProviderRegistry registry, ProviderRepository repository) {
        this.registry = registry;
        this.repository = repository;
    }

    @Cacheable(cacheNames = CacheConfig.SEARCH, key = "#provider.code() + '|' + #query")
    public List<RemoteTitle> search(ContentProvider provider, SearchQuery query) {
        return provider.search(query);
    }

    @Cacheable(cacheNames = CacheConfig.DETAILS, key = "#code + '|' + #externalId")
    public RemoteTitleDetails details(String code, String externalId) {
        return registry.require(code).getDetails(externalId);
    }

    @Cacheable(cacheNames = CacheConfig.CHAPTERS, key = "#code + '|' + #externalId + '|' + #languages")
    public List<RemoteChapter> chapters(String code, String externalId, Set<String> languages) {
        return registry.require(code).getChapters(externalId, languages);
    }

    @CacheEvict(cacheNames = CacheConfig.CHAPTERS, allEntries = true)
    public void evictChapters() {
        // apenas invalida o cache
    }

    @Cacheable(cacheNames = CacheConfig.DISCOVER, key = "#provider.code() + '|' + #kind + '|' + #languages + '|' + #page")
    public List<RemoteTitle> discover(ContentProvider provider, DiscoverKind kind, Set<String> languages, int page) {
        return provider.discover(kind, languages, page);
    }

    @Cacheable(cacheNames = CacheConfig.PAGES, key = "#code + '|' + #chapterExternalId")
    public List<PageRef> pages(String code, String chapterExternalId) {
        return registry.require(code).getPages(chapterExternalId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordStatus(String code, ProviderHealth health) {
        repository.findByCode(code).ifPresent(p -> {
            p.setLastStatus(health.status().name());
            p.setLastError(health.status() == ProviderHealth.Status.ONLINE ? null : truncate(health.message()));
            p.setLastCheckedAt(Instant.now());
        });
    }

    private static String truncate(String s) {
        return s == null ? null : s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
