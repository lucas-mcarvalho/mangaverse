package com.mangaverse.provider.spi;

import java.util.List;
import java.util.Set;

public record RemoteTitleDetails(
        String providerCode,
        String externalId,
        String title,
        List<String> altTitles,
        String description,
        String coverUrl,
        ContentType type,
        List<String> authors,
        List<String> artists,
        String publisher,
        List<String> genres,
        List<String> characters,
        Set<String> languages,
        String originalLanguage,
        String status,
        Integer year,
        String url) {
}
