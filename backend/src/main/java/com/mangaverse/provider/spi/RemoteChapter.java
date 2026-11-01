package com.mangaverse.provider.spi;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Capítulo (mangá) ou edição (HQ) informado por um provedor.
 *
 * @param readable     páginas podem ser obtidas pelo provedor
 * @param downloadable o provedor permite baixar as páginas de forma autorizada
 * @param externalUrl  link oficial quando o capítulo só existe no site da editora
 */
public record RemoteChapter(
        String externalId,
        BigDecimal number,
        String numberLabel,
        String volume,
        String title,
        String language,
        String scanlator,
        Instant publishedAt,
        Integer pageCount,
        boolean readable,
        boolean downloadable,
        String externalUrl) {
}
