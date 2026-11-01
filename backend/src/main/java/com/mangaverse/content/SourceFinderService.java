package com.mangaverse.content;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mangaverse.common.ApiException;
import com.mangaverse.common.TextNormalizer;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.search.AggregatedResult;
import com.mangaverse.search.SearchService;
import com.mangaverse.settings.SettingsService;

/**
 * Procura o mesmo título nas demais fontes habilitadas e as vincula ao conteúdo, para preencher capítulos
 * que a fonte atual não hospeda (ex.: capítulos do MangaDex que só existem como link para o site oficial).
 */
@Service
public class SourceFinderService {

    private static final Logger log = LoggerFactory.getLogger(SourceFinderService.class);

    private final ContentRepository contents;
    private final ContentSourceRepository sources;
    private final ChapterRepository chapters;
    private final ContentService contentService;
    private final SearchService searchService;
    private final ProviderService providerService;
    private final SettingsService settings;

    public SourceFinderService(ContentRepository contents, ContentSourceRepository sources, ChapterRepository chapters,
                               ContentService contentService, SearchService searchService, ProviderService providerService,
                               SettingsService settings) {
        this.contents = contents;
        this.sources = sources;
        this.chapters = chapters;
        this.contentService = contentService;
        this.searchService = searchService;
        this.providerService = providerService;
        this.settings = settings;
    }

    public record FindResult(List<String> linked, int newChapters, List<String> skipped, List<String> errors) {
    }

    public FindResult findMoreSources(UUID contentId) {
        Content content = contents.findById(contentId).orElseThrow(() -> ApiException.notFound("Conteúdo não encontrado."));
        Set<String> linkedKeys = new HashSet<>();
        Set<String> linkedProviders = new HashSet<>();
        for (ContentSource s : sources.findByContentId(contentId)) {
            linkedKeys.add(s.getProvider().getCode() + "|" + s.getExternalId());
            linkedProviders.add(s.getProvider().getCode());
        }
        long before = chapters.countByContentId(contentId);
        SearchService.SearchResponse response = searchService.search(content.getTitle(), Set.of(content.getType()), null);

        List<String> errors = new ArrayList<>();
        response.providers().stream().filter(p -> !"OK".equals(p.status()))
                .forEach(p -> errors.add(p.name() + ": " + p.message()));

        Set<BigDecimal> readable = readableNumbers(contentId);
        Set<String> languages = new LinkedHashSet<>(settings.languages());
        List<String> linked = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (AggregatedResult group : response.results()) {
            if (!sameWork(group, content, linkedKeys)) {
                continue;
            }
            for (AggregatedResult.SourceRef ref : group.sources()) {
                if (linkedProviders.contains(ref.providerCode()) || ref.inLibrary()) {
                    continue; // uma fonte por provedor; nunca "roubar" fonte de outro título
                }
                try {
                    // Só vincula se a fonte trouxer ao menos um capítulo legível que ainda não temos
                    // (evita duplicar a mesma origem, ex.: MangaDex direto e via Suwayomi)
                    boolean addsSomething = providerService.chapters(ref.providerCode(), ref.externalId(), languages).stream()
                            .anyMatch(c -> c.readable() && c.number() != null
                                    && readable.stream().noneMatch(n -> n.compareTo(c.number()) == 0));
                    if (!addsSomething) {
                        skipped.add(ref.providerName());
                        continue;
                    }
                    contentService.importFromProvider(ref.providerCode(), ref.externalId(), contentId);
                    linkedProviders.add(ref.providerCode());
                    linked.add(ref.providerName());
                    readable.addAll(readableNumbers(contentId));
                } catch (RuntimeException e) {
                    log.warn("Não foi possível vincular {} a {}: {}", ref.providerCode(), contentId, e.getMessage());
                    errors.add(ref.providerName() + ": " + e.getMessage());
                }
            }
        }
        return new FindResult(linked, (int) Math.max(0, chapters.countByContentId(contentId) - before), skipped, errors);
    }

    private Set<BigDecimal> readableNumbers(UUID contentId) {
        Set<BigDecimal> out = new HashSet<>();
        for (Chapter c : chapters.findByContentOrdered(contentId)) {
            if ((c.isReadable() || c.isLocal()) && c.getNumber() != null) {
                out.add(c.getNumber());
            }
        }
        return out;
    }

    /**
     * O grupo da pesquisa é a mesma obra quando já contém uma fonte vinculada a este conteúdo
     * (o agregador junta por títulos alternativos) ou quando algum nome coincide exatamente.
     */
    static boolean sameWork(AggregatedResult group, Content content, Set<String> linkedKeys) {
        if (group.type() != null && group.type().isComic() != content.getType().isComic()) {
            return false;
        }
        if (group.sources().stream().anyMatch(s -> linkedKeys.contains(s.providerCode() + "|" + s.externalId()))) {
            return true;
        }
        String target = content.getNormalizedTitle();
        if (TextNormalizer.normalizeTitle(group.title()).equals(target)) {
            return true;
        }
        return group.altTitles().stream().anyMatch(a -> TextNormalizer.normalizeTitle(a).equals(target));
    }
}
