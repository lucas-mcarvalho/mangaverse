package com.mangaverse.provider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mangaverse.common.ApiException;
import com.mangaverse.config.AppProperties;
import com.mangaverse.provider.comicvine.ComicVineProvider;
import com.mangaverse.provider.mangadex.MangaDexProvider;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.suwayomi.SuwayomiClient;
import com.mangaverse.provider.suwayomi.SuwayomiSourceProvider;

/** Resolve códigos de provedor para implementações, respeitando o que está habilitado no banco. */
@Component
public class ProviderRegistry {

    private final MangaDexProvider mangaDex;
    private final ComicVineProvider comicVine;
    private final SuwayomiClient suwayomi;
    private final ProviderRepository repository;
    private final AppProperties properties;

    public ProviderRegistry(MangaDexProvider mangaDex, ComicVineProvider comicVine, SuwayomiClient suwayomi,
                            ProviderRepository repository, AppProperties properties) {
        this.mangaDex = mangaDex;
        this.comicVine = comicVine;
        this.suwayomi = suwayomi;
        this.repository = repository;
        this.properties = properties;
    }

    public Optional<ContentProvider> find(String code) {
        if (MangaDexProvider.CODE.equals(code)) {
            return properties.providers().mangadex().enabled() ? Optional.of(mangaDex) : Optional.empty();
        }
        if (ComicVineProvider.CODE.equals(code)) {
            return properties.providers().comicvine().enabled() ? Optional.of(comicVine) : Optional.empty();
        }
        if (SuwayomiSourceProvider.handles(code) && suwayomi.enabled()) {
            return repository.findByCode(code).map(this::suwayomiProvider);
        }
        return Optional.empty();
    }

    public ContentProvider require(String code) {
        return find(code).orElseThrow(() -> ApiException.notFound("Provedor não encontrado ou desabilitado: " + code));
    }

    /**
     * Fontes consultadas numa pesquisa e as que ficaram de fora pelo limite do Suwayomi.
     */
    public record SearchPlan(List<ContentProvider> selected, List<Provider> skipped, int limit) {
    }

    /** Provedores habilitados para a pesquisa global (sem filtro de tipo). */
    public List<ContentProvider> searchable() {
        return searchPlan(Set.of()).selected();
    }

    /**
     * Monta a pesquisa respeitando o tipo procurado: fontes marcadas com outro tipo não são consultadas,
     * fontes do tipo procurado têm preferência, e o excedente do limite de fontes do Suwayomi é informado.
     */
    public SearchPlan searchPlan(Set<ContentType> types) {
        List<ContentProvider> out = new ArrayList<>();
        List<Provider> suwayomiCandidates = new ArrayList<>();
        for (Provider p : repository.findByEnabledTrueAndSupportsSearchTrueOrderByPriorityAsc()) {
            if (p.getType() == ProviderType.SUWAYOMI_SOURCE) {
                if (!suwayomi.enabled()) {
                    continue;
                }
                Optional<ContentType> forced = p.forcedType();
                if (!types.isEmpty() && forced.isPresent() && !types.contains(forced.get())) {
                    continue; // ex.: fonte de mangá numa pesquisa só de HQs
                }
                suwayomiCandidates.add(p);
            } else if (!ComicVineProvider.CODE.equals(p.getCode()) || properties.providers().comicvine().configured()) {
                find(p.getCode()).ifPresent(out::add);
            }
        }
        if (!types.isEmpty()) {
            // ordenação estável: mantém a prioridade dentro de cada grupo
            suwayomiCandidates.sort(Comparator.comparingInt(p -> p.forcedType().map(types::contains).orElse(false) ? 0 : 1));
        }
        int limit = Math.max(1, suwayomi.maxSourcesPerSearch());
        List<Provider> skipped = new ArrayList<>();
        for (int i = 0; i < suwayomiCandidates.size(); i++) {
            if (i < limit) {
                out.add(suwayomiProvider(suwayomiCandidates.get(i)));
            } else {
                skipped.add(suwayomiCandidates.get(i));
            }
        }
        return new SearchPlan(out, skipped, limit);
    }

    public List<ContentProvider> discoverable() {
        return searchable().stream().filter(p -> p.capabilities().discover()).toList();
    }

    private ContentProvider suwayomiProvider(Provider p) {
        return new SuwayomiSourceProvider(suwayomi, p.getExternalRef(), p.getName(), p.getLanguage(), p.forcedType().orElse(null));
    }
}
