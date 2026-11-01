package com.mangaverse.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.mangaverse.common.ApiException;
import com.mangaverse.config.AppProperties;
import com.mangaverse.provider.comicvine.ComicVineProvider;
import com.mangaverse.provider.mangadex.MangaDexProvider;
import com.mangaverse.provider.spi.ContentProvider;
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

    /** Provedores habilitados para a pesquisa global, limitando a quantidade de fontes do Suwayomi. */
    public List<ContentProvider> searchable() {
        List<ContentProvider> out = new ArrayList<>();
        int suwayomiCount = 0;
        for (Provider p : repository.findByEnabledTrueAndSupportsSearchTrueOrderByPriorityAsc()) {
            if (p.getType() == ProviderType.SUWAYOMI_SOURCE) {
                if (!suwayomi.enabled() || suwayomiCount >= suwayomi.maxSourcesPerSearch()) {
                    continue;
                }
                suwayomiCount++;
                out.add(suwayomiProvider(p));
            } else if (!ComicVineProvider.CODE.equals(p.getCode()) || properties.providers().comicvine().configured()) {
                find(p.getCode()).ifPresent(out::add);
            }
        }
        return out;
    }

    public List<ContentProvider> discoverable() {
        return searchable().stream().filter(p -> p.capabilities().discover()).toList();
    }

    private ContentProvider suwayomiProvider(Provider p) {
        return new SuwayomiSourceProvider(suwayomi, p.getExternalRef(), p.getName(), p.getLanguage());
    }
}
