package com.mangaverse.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mangaverse.config.AppProperties;
import com.mangaverse.provider.comicvine.ComicVineProvider;
import com.mangaverse.provider.mangadex.MangaDexProvider;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.suwayomi.SuwayomiClient;

class ProviderRegistryTest {

    private static Provider source(String id, String name, int priority, String type) {
        Provider p = new Provider("suwayomi:" + id, name, ProviderType.SUWAYOMI_SOURCE);
        p.setExternalRef(id);
        p.setLanguage("pt-br");
        p.setPriority(priority);
        p.setDefaultContentType(type);
        return p;
    }

    private ProviderRegistry registry(int limit, List<Provider> enabled) {
        AppProperties props = new AppProperties(null, null, null, new AppProperties.Providers(
                new AppProperties.MangaDex(true, "x", "x", "", 1),
                new AppProperties.ComicVine(true, "x", "", 1),
                new AppProperties.Suwayomi(true, "http://s", null, null, null, 1, limit)));
        SuwayomiClient client = new SuwayomiClient(props.providers().suwayomi(), MockHttp.support()); // sem chamadas HTTP
        ProviderRepository repo = mock(ProviderRepository.class);
        when(repo.findByEnabledTrueAndSupportsSearchTrueOrderByPriorityAsc()).thenReturn(enabled);
        return new ProviderRegistry(mock(MangaDexProvider.class), mock(ComicVineProvider.class), client, repo, props);
    }

    @Test
    void comicSearchSkipsMangaSourcesAndPrefersComicSourcesWithinLimit() {
        ProviderRegistry registry = registry(2, List.of(
                source("1", "Scan A", 20, "AUTO"),
                source("2", "Scan B", 20, "MANGA"),
                source("3", "Scan C", 20, "AUTO"),
                source("4", "HQ Now!", 20, "COMIC"),
                source("5", "Read Comics Online", 60, "COMIC")));

        ProviderRegistry.SearchPlan plan = registry.searchPlan(Set.of(ContentType.COMIC));

        assertThat(plan.selected()).extracting(ContentProvider::code).containsExactly("suwayomi:4", "suwayomi:5");
        // fonte de mangá não conta nem aparece; as automáticas excedentes são informadas
        assertThat(plan.skipped()).extracting(Provider::getName).containsExactly("Scan A", "Scan C");
    }

    @Test
    void searchWithoutTypeUsesPriorityOrderAndReportsSkipped() {
        ProviderRegistry registry = registry(2, List.of(
                source("1", "A", 20, "AUTO"), source("2", "B", 20, "MANGA"), source("3", "C", 60, "COMIC")));

        ProviderRegistry.SearchPlan plan = registry.searchPlan(Set.of());

        assertThat(plan.selected()).extracting(ContentProvider::code).containsExactly("suwayomi:1", "suwayomi:2");
        assertThat(plan.skipped()).extracting(Provider::getName).containsExactly("C");
        assertThat(registry.searchable()).hasSize(2);
    }
}
