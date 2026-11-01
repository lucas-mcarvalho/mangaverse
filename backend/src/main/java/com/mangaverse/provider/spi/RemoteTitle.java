package com.mangaverse.provider.spi;

import java.util.List;
import java.util.Set;

/** Resultado de pesquisa retornado por um provedor. */
public record RemoteTitle(
        String providerCode,
        String providerName,
        String externalId,
        String title,
        List<String> altTitles,
        String description,
        String coverUrl,
        ContentType type,
        Set<String> languages,
        Integer year,
        String status,
        String url,
        String publisher) {
}
