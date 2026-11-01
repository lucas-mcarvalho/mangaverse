package com.mangaverse.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.RemoteTitle;

class SearchAggregatorTest {

    private final SearchAggregator aggregator = new SearchAggregator("pt-br", Map.of("mangadex", 10, "suwayomi:1", 20, "comicvine", 50));

    static RemoteTitle title(String provider, String id, String title, List<String> alt, ContentType type, Set<String> langs, Integer year) {
        return new RemoteTitle(provider, provider, id, title, alt, "desc " + id, "cover-" + id, type, langs, year, null, null, null);
    }

    @Test
    void mergesSameTitleFromDifferentProviders() {
        List<AggregatedResult> results = aggregator.aggregate("one piece", List.of(
                title("mangadex", "a", "One Piece", List.of(), ContentType.MANGA, Set.of("en"), 1997),
                title("suwayomi:1", "7", "ONE PIECE", List.of(), ContentType.MANGA, Set.of("pt-br"), null)));
        assertThat(results).hasSize(1);
        AggregatedResult r = results.get(0);
        assertThat(r.sources()).extracting(AggregatedResult.SourceRef::providerCode).containsExactly("mangadex", "suwayomi:1");
        assertThat(r.languages()).contains("en", "pt-br");
        assertThat(r.hasPreferredLanguage()).isTrue();
    }

    @Test
    void mergesUsingAlternativeTitlesAndAccentsInsensitive() {
        List<AggregatedResult> results = aggregator.aggregate("solo leveling", List.of(
                title("mangadex", "a", "Na Honjaman Level-Up", List.of("Solo Leveling"), ContentType.MANHWA, Set.of("pt-br"), 2018),
                title("suwayomi:1", "9", "Solo Leveling", List.of(), ContentType.MANHWA, Set.of("pt-br"), null)));
        assertThat(results).hasSize(1);
        assertThat(results.get(0).sources()).hasSize(2);
    }

    @Test
    void neverMergesTwoEntriesFromTheSameProvider() {
        List<AggregatedResult> results = aggregator.aggregate("berserk", List.of(
                title("mangadex", "a", "Berserk", List.of(), ContentType.MANGA, Set.of("en"), 1989),
                title("mangadex", "b", "Berserk", List.of(), ContentType.MANGA, Set.of("en"), 1989)));
        assertThat(results).hasSize(2);
    }

    @Test
    void doesNotMergeComicsWithMangaNorComicsFromDifferentYears() {
        List<AggregatedResult> results = aggregator.aggregate("batman", List.of(
                title("comicvine", "1", "Batman", List.of(), ContentType.COMIC, Set.of("en"), 2011),
                title("suwayomi:1", "2", "Batman", List.of(), ContentType.COMIC, Set.of("en"), 2016),
                title("mangadex", "3", "Batman", List.of(), ContentType.MANGA, Set.of("en"), null)));
        assertThat(results).hasSize(3);
    }

    @Test
    void prioritizesPortugueseAndExactMatches() {
        List<AggregatedResult> results = aggregator.aggregate("invincible", List.of(
                title("mangadex", "x", "Invincible Fighter", List.of(), ContentType.MANGA, Set.of("en"), null),
                title("suwayomi:1", "y", "Invincible", List.of(), ContentType.COMIC, Set.of("pt-br"), 2003),
                title("comicvine", "z", "Invincible", List.of(), ContentType.COMIC, Set.of("en"), 2003)));
        assertThat(results.get(0).title()).isEqualTo("Invincible");
        assertThat(results.get(0).hasPreferredLanguage()).isTrue();
        assertThat(results.get(0).sources()).hasSize(2);
        assertThat(results.get(1).title()).isEqualTo("Invincible Fighter");
    }

    @Test
    void exactAlternativeTitleOutranksPrefixMatch() {
        List<AggregatedResult> results = aggregator.aggregate("solo leveling", List.of(
                title("mangadex", "a", "Solo Leveling (Book Version)", List.of(), ContentType.MANHWA, Set.of("pt-br"), null),
                title("mangadex", "b", "Na Honjaman Level-Up", List.of("Solo Leveling"), ContentType.MANHWA, Set.of("pt-br"), null)));
        assertThat(results.get(0).title()).isEqualTo("Na Honjaman Level-Up");
    }

    @Test
    void ignoresInvalidItems() {
        assertThat(aggregator.aggregate("x", java.util.Arrays.asList(null,
                title("mangadex", null, "Sem id", List.of(), ContentType.MANGA, Set.of(), null)))).isEmpty();
    }
}
