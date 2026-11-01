package com.mangaverse.search;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.mangaverse.content.ContentSourceRepository;
import com.mangaverse.provider.Provider;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderRepository;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.provider.spi.SearchQuery;
import com.mangaverse.settings.SettingsService;

import jakarta.annotation.PreDestroy;

/** Consulta os provedores habilitados em paralelo; falhas de uma fonte não interrompem as demais. */
@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);
    private static final Duration PROVIDER_TIMEOUT = Duration.ofSeconds(25);

    private final ProviderRegistry registry;
    private final ProviderService providerService;
    private final ProviderRepository providerRepository;
    private final ContentSourceRepository contentSources;
    private final SettingsService settings;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SearchService(ProviderRegistry registry, ProviderService providerService, ProviderRepository providerRepository,
                         ContentSourceRepository contentSources, SettingsService settings) {
        this.registry = registry;
        this.providerService = providerService;
        this.providerRepository = providerRepository;
        this.contentSources = contentSources;
        this.settings = settings;
    }

    public record ProviderOutcome(String code, String name, String status, int count, String message, long tookMs) {
    }

    public record SearchResponse(String query, List<AggregatedResult> results, List<ProviderOutcome> providers) {
    }

    public SearchResponse search(String text, Set<ContentType> types, Set<String> providerCodes) {
        String q = text.trim();
        Set<ContentType> typeFilter = types == null || types.isEmpty() ? EnumSet.noneOf(ContentType.class) : EnumSet.copyOf(types);
        SearchQuery query = new SearchQuery(q, new LinkedHashSet<>(settings.languages()), typeFilter, 20);
        List<ContentProvider> providers = registry.searchable().stream()
                .filter(p -> providerCodes == null || providerCodes.isEmpty() || providerCodes.contains(p.code()))
                .toList();

        Map<String, CompletableFuture<List<RemoteTitle>>> futures = new HashMap<>();
        Map<String, Long> started = new HashMap<>();
        for (ContentProvider p : providers) {
            started.put(p.code(), System.nanoTime());
            futures.put(p.code(), CompletableFuture.supplyAsync(() -> providerService.search(p, query), executor)
                    .orTimeout(PROVIDER_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
        }

        List<RemoteTitle> all = new ArrayList<>();
        List<ProviderOutcome> outcomes = new ArrayList<>();
        for (ContentProvider p : providers) {
            long took;
            try {
                List<RemoteTitle> found = futures.get(p.code()).join();
                took = elapsed(started.get(p.code()));
                List<RemoteTitle> filtered = found.stream()
                        .filter(t -> typeFilter.isEmpty() || t.type() == null || typeFilter.contains(t.type()))
                        .toList();
                all.addAll(filtered);
                outcomes.add(new ProviderOutcome(p.code(), p.displayName(), "OK", filtered.size(), null, took));
            } catch (RuntimeException e) {
                took = elapsed(started.get(p.code()));
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                boolean timeout = cause instanceof TimeoutException;
                String message = timeout ? "Tempo limite excedido" : cause instanceof ProviderException pe ? pe.getMessage() : "Erro inesperado";
                log.warn("Pesquisa falhou no provedor {}: {}", p.code(), message);
                outcomes.add(new ProviderOutcome(p.code(), p.displayName(), timeout ? "TIMEOUT" : "ERROR", 0, message, took));
                if (cause instanceof ProviderException) {
                    providerService.recordStatus(p.code(), ProviderHealth.offline(message));
                }
            }
        }

        Map<String, Integer> priorities = new HashMap<>();
        for (Provider p : providerRepository.findAll()) {
            priorities.put(p.getCode(), p.getPriority());
        }
        List<AggregatedResult> results = new SearchAggregator(settings.preferredLanguage(), priorities).aggregate(q, all);
        return new SearchResponse(q, markLibrary(results), outcomes);
    }

    /** Indica quais resultados já estão na biblioteca. */
    List<AggregatedResult> markLibrary(List<AggregatedResult> results) {
        List<AggregatedResult> out = new ArrayList<>(results.size());
        for (AggregatedResult r : results) {
            String contentId = null;
            List<AggregatedResult.SourceRef> refs = new ArrayList<>();
            for (AggregatedResult.SourceRef s : r.sources()) {
                var existing = contentSources.findByProviderCodeAndExternalId(s.providerCode(), s.externalId());
                if (existing.isPresent() && contentId == null) {
                    contentId = existing.get().getContent().getId().toString();
                }
                refs.add(new AggregatedResult.SourceRef(s.providerCode(), s.providerName(), s.externalId(), s.title(),
                        s.url(), s.languages(), s.coverUrl(), existing.isPresent()));
            }
            out.add(r.withLibrary(contentId, refs));
        }
        return out;
    }

    private static long elapsed(Long startNanos) {
        return startNanos == null ? 0 : Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }
}
