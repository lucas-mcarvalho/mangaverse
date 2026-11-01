package com.mangaverse.provider.spi;

import java.util.List;
import java.util.Set;

/**
 * Abstração comum para fontes de conteúdo e metadados.
 * Cada implementação declara em {@link #capabilities()} o que de fato suporta.
 */
public interface ContentProvider {

    String code();

    String displayName();

    ProviderCapabilities capabilities();

    List<RemoteTitle> search(SearchQuery query);

    RemoteTitleDetails getDetails(String externalId);

    List<RemoteChapter> getChapters(String externalId, Set<String> languages);

    Set<String> getAvailableLanguages(String externalId);

    /** Retorna os capítulos que não estão entre os já conhecidos. */
    default List<RemoteChapter> checkUpdates(String externalId, Set<String> knownChapterIds, Set<String> languages) {
        return getChapters(externalId, languages).stream()
                .filter(c -> !knownChapterIds.contains(c.externalId()))
                .toList();
    }

    default List<PageRef> getPages(String chapterExternalId) {
        throw new ProviderException(code(), "Este provedor não oferece leitura de páginas.");
    }

    default FetchedImage fetchPage(PageRef page) {
        throw new ProviderException(code(), "Este provedor não oferece leitura de páginas.");
    }

    default List<RemoteTitle> discover(DiscoverKind kind, Set<String> languages, int page) {
        throw new ProviderException(code(), "Este provedor não oferece exploração.");
    }

    ProviderHealth health();
}
