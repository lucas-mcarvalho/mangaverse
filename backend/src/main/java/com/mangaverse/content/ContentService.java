package com.mangaverse.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mangaverse.common.ApiException;
import com.mangaverse.common.TextNormalizer;
import com.mangaverse.provider.Provider;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderRepository;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitleDetails;
import com.mangaverse.settings.SettingsService;

/**
 * Importa títulos de provedores para o banco e mantém a lista de capítulos sincronizada.
 * Chamadas externas acontecem fora de transações; somente a persistência é transacional.
 */
@Service
public class ContentService {

    private static final Logger log = LoggerFactory.getLogger(ContentService.class);

    private final ContentRepository contents;
    private final ContentSourceRepository sources;
    private final ChapterRepository chapters;
    private final ProviderRepository providers;
    private final ProviderRegistry registry;
    private final ProviderService providerService;
    private final SettingsService settings;
    private final TransactionTemplate tx;

    public ContentService(ContentRepository contents, ContentSourceRepository sources, ChapterRepository chapters,
                          ProviderRepository providers, ProviderRegistry registry, ProviderService providerService,
                          SettingsService settings, PlatformTransactionManager txManager) {
        this.contents = contents;
        this.sources = sources;
        this.chapters = chapters;
        this.providers = providers;
        this.registry = registry;
        this.providerService = providerService;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Importa (ou reaproveita) o título de um provedor. Se {@code attachTo} for informado, a fonte é vinculada
     * a esse conteúdo existente (ex.: metadados do Comic Vine para uma HQ local).
     */
    public Content importFromProvider(String providerCode, String externalId, UUID attachTo) {
        Optional<ContentSource> existing = sources.findByProviderCodeAndExternalId(providerCode, externalId);
        if (existing.isPresent()) {
            if (attachTo != null && !existing.get().getContent().getId().equals(attachTo)) {
                throw ApiException.conflict("Esta fonte já está vinculada a outro título da biblioteca.");
            }
            return existing.get().getContent();
        }
        ContentProvider provider = registry.require(providerCode);
        RemoteTitleDetails details = providerService.details(providerCode, externalId);
        Provider providerRow = providers.findByCode(providerCode)
                .orElseThrow(() -> ApiException.notFound("Provedor não cadastrado: " + providerCode));

        UUID sourceId = tx.execute(status -> {
            Content content = attachTo != null
                    ? contents.findById(attachTo).orElseThrow(() -> ApiException.notFound("Conteúdo não encontrado."))
                    : findMergeCandidate(details, providerRow).orElse(null);
            if (content == null) {
                content = new Content(details.title(), details.type() == null ? ContentType.MANGA : details.type());
                applyDetails(content, details, true);
            } else {
                applyDetails(content, details, provider.capabilities().metadataOnly());
            }
            contents.save(content);
            ContentSource source = new ContentSource(content, providerRow, externalId);
            source.setUrl(details.url());
            source.setTitle(details.title());
            source.setLanguage(details.languages() == null || details.languages().isEmpty() ? null
                    : details.languages().contains(settings.preferredLanguage()) ? settings.preferredLanguage()
                    : details.languages().iterator().next());
            source.setCoverUrl(details.coverUrl());
            return sources.save(source).getId();
        });
        if (provider.capabilities().chapters()) {
            try {
                syncChapters(sourceId, false);
            } catch (RuntimeException e) {
                log.warn("Título importado, mas a lista de capítulos não pôde ser obtida: {}", e.getMessage());
            }
        }
        return sources.findById(sourceId).map(ContentSource::getContent).orElseThrow();
    }

    /** Sincroniza os capítulos de uma fonte e retorna os capítulos recém-descobertos. */
    public List<Chapter> syncChapters(UUID sourceId, boolean fresh) {
        ContentSource source = sources.findById(sourceId).orElseThrow(() -> ApiException.notFound("Fonte não encontrada."));
        String code = source.getProvider().getCode();
        Set<String> languages = new LinkedHashSet<>(settings.languages());
        List<RemoteChapter> remote = fresh
                ? registry.require(code).getChapters(source.getExternalId(), languages)
                : providerService.chapters(code, source.getExternalId(), languages);
        if (fresh) {
            providerService.evictChapters();
        }
        String preferred = settings.preferredLanguage();
        boolean metadataOnly = source.getProvider().isMetadataOnly();
        return tx.execute(status -> {
            ContentSource src = sources.findById(sourceId).orElseThrow();
            Content content = src.getContent();
            Map<String, Chapter> byExternal = new HashMap<>();
            List<Chapter> unlinkedLocal = new ArrayList<>();
            for (Chapter c : chapters.findByContentOrdered(content.getId())) {
                if (c.getSource() != null && c.getSource().getId().equals(sourceId)) {
                    byExternal.put(c.getExternalId(), c);
                } else if (c.getSource() == null && c.isLocal()) {
                    unlinkedLocal.add(c);
                }
            }
            List<Chapter> created = new ArrayList<>();
            for (RemoteChapter r : remote) {
                if (r.externalId() == null) {
                    continue;
                }
                Chapter chapter = byExternal.get(r.externalId());
                if (chapter == null) {
                    chapter = adoptLocal(unlinkedLocal, r, preferred, metadataOnly, src);
                    if (chapter == null) {
                        chapter = new Chapter(content, src, r.externalId());
                        created.add(chapter);
                    }
                    byExternal.put(r.externalId(), chapter);
                }
                apply(chapter, r);
                chapters.save(chapter);
            }
            src.setLastSyncedAt(Instant.now());
            return created;
        });
    }

    /** Vincula um capítulo local já existente (mesmo número) à fonte remota, evitando duplicatas e downloads repetidos. */
    private static Chapter adoptLocal(List<Chapter> unlinkedLocal, RemoteChapter r, String preferred, boolean metadataOnly,
                                      ContentSource src) {
        if (r.number() == null || !(metadataOnly || Objects.equals(preferred, r.language()))) {
            return null;
        }
        for (var it = unlinkedLocal.iterator(); it.hasNext(); ) {
            Chapter local = it.next();
            if (local.getNumber() != null && local.getNumber().compareTo(r.number()) == 0) {
                it.remove();
                local.linkTo(src, r.externalId());
                return local;
            }
        }
        return null;
    }

    private static void apply(Chapter c, RemoteChapter r) {
        if (r.number() != null) {
            c.setNumber(r.number());
        }
        if (r.numberLabel() != null) {
            c.setNumberLabel(r.numberLabel());
        }
        c.setVolume(r.volume() != null ? r.volume() : c.getVolume());
        c.setTitle(r.title() != null ? r.title() : c.getTitle());
        c.setLanguage(r.language() != null ? r.language() : c.getLanguage());
        c.setScanlator(r.scanlator());
        c.setPublishedAt(r.publishedAt());
        if (r.pageCount() != null) {
            c.setPageCount(r.pageCount());
        }
        if (!c.isLocal()) {
            c.setReadable(r.readable());
        }
        c.setDownloadable(r.downloadable());
        c.setExternalUrl(r.externalUrl());
    }

    private Optional<Content> findMergeCandidate(RemoteTitleDetails details, Provider provider) {
        if (details.type() != null && details.type().isComic()) {
            return Optional.empty(); // HQs homônimas costumam ser séries diferentes
        }
        String normalized = TextNormalizer.normalizeTitle(details.title());
        return contents.findByNormalizedTitle(normalized).stream()
                .filter(c -> !c.getType().isComic())
                .filter(c -> sources.findByContentId(c.getId()).stream().noneMatch(s -> s.getProvider().getId().equals(provider.getId())))
                .findFirst();
    }

    /** Copia metadados; com {@code overwrite} falso, apenas preenche campos vazios. */
    static void applyDetails(Content c, RemoteTitleDetails d, boolean overwrite) {
        if (overwrite || isBlank(c.getDescription())) {
            c.setDescription(d.description() != null ? d.description() : c.getDescription());
        }
        if (overwrite || c.getAuthors().isEmpty()) {
            c.setAuthors(d.authors() != null && !d.authors().isEmpty() ? d.authors() : c.getAuthors());
        }
        if (overwrite || c.getArtists().isEmpty()) {
            c.setArtists(d.artists() != null && !d.artists().isEmpty() ? d.artists() : c.getArtists());
        }
        if (overwrite || isBlank(c.getPublisher())) {
            c.setPublisher(d.publisher() != null ? d.publisher() : c.getPublisher());
        }
        if (overwrite || c.getGenres().isEmpty()) {
            c.setGenres(d.genres() != null && !d.genres().isEmpty() ? d.genres() : c.getGenres());
        }
        if (d.characters() != null && !d.characters().isEmpty() && (overwrite || c.getCharacters().isEmpty())) {
            c.setCharacters(d.characters());
        }
        if (overwrite || isBlank(c.getPublicationStatus())) {
            c.setPublicationStatus(d.status() != null ? d.status() : c.getPublicationStatus());
        }
        if (overwrite || c.getYear() == null) {
            c.setYear(d.year() != null ? d.year() : c.getYear());
        }
        if (d.coverUrl() != null && (overwrite || isBlank(c.getCoverUrl()) || c.getCoverUrl().startsWith(Covers.LOCAL))) {
            c.setCoverUrl(d.coverUrl());
        }
        if (isBlank(c.getLanguage()) && d.originalLanguage() != null) {
            c.setLanguage(d.originalLanguage());
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
