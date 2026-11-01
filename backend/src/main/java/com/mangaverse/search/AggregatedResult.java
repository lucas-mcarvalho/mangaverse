package com.mangaverse.search;

import java.util.List;
import java.util.Set;

import com.mangaverse.provider.spi.ContentType;

public record AggregatedResult(
        String key,
        String title,
        List<String> altTitles,
        String description,
        String coverUrl,
        ContentType type,
        Set<String> languages,
        boolean hasPreferredLanguage,
        Integer year,
        String status,
        String publisher,
        List<SourceRef> sources,
        double score,
        String libraryContentId) {

    public record SourceRef(String providerCode, String providerName, String externalId, String title,
                            String url, Set<String> languages, String coverUrl, boolean inLibrary) {
    }

    public AggregatedResult withLibrary(String contentId, List<SourceRef> updatedSources) {
        return new AggregatedResult(key, title, altTitles, description, coverUrl, type, languages, hasPreferredLanguage,
                year, status, publisher, updatedSources, score, contentId);
    }
}
