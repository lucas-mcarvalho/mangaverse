package com.mangaverse.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.mangaverse.common.TextNormalizer;
import com.mangaverse.provider.spi.RemoteTitle;

/**
 * Agrega resultados de vários provedores, removendo duplicatas e priorizando o idioma preferido.
 *
 * Regras de deduplicação:
 * - dois resultados são o mesmo título quando algum nome normalizado (título ou alternativo) coincide;
 * - mangás/manhwas/manhuas nunca são mesclados com HQs;
 * - HQs com anos de início diferentes são séries distintas (ex.: Batman 2011 x 2016);
 * - dois resultados do mesmo provedor nunca são mesclados (são entradas distintas na fonte).
 */
public class SearchAggregator {

    private final String preferredLanguage;
    private final Map<String, Integer> providerPriority;

    public SearchAggregator(String preferredLanguage, Map<String, Integer> providerPriority) {
        this.preferredLanguage = preferredLanguage == null ? "pt-br" : preferredLanguage;
        this.providerPriority = providerPriority == null ? Map.of() : providerPriority;
    }

    public List<AggregatedResult> aggregate(String query, List<RemoteTitle> items) {
        List<Group> groups = new ArrayList<>();
        Map<String, List<Group>> byName = new HashMap<>();
        for (RemoteTitle item : items) {
            if (item == null || item.title() == null || item.externalId() == null) {
                continue;
            }
            Set<String> names = namesOf(item);
            Group target = null;
            for (String name : names) {
                for (Group g : byName.getOrDefault(name, List.of())) {
                    if (g.accepts(item)) {
                        target = g;
                        break;
                    }
                }
                if (target != null) {
                    break;
                }
            }
            if (target == null) {
                target = new Group(groups.size());
                groups.add(target);
            }
            target.add(item);
            for (String name : names) {
                List<Group> list = byName.computeIfAbsent(name, k -> new ArrayList<>());
                if (!list.contains(target)) {
                    list.add(target);
                }
            }
        }
        // Empates preservam a ordem de relevância devolvida pelas fontes
        return groups.stream()
                .sorted(Comparator.comparingDouble((Group g) -> g.score(query)).reversed()
                        .thenComparingInt(g -> g.order))
                .map(g -> g.toResult(query))
                .toList();
    }

    static Set<String> namesOf(RemoteTitle item) {
        Set<String> names = new LinkedHashSet<>();
        names.add(TextNormalizer.normalizeTitle(item.title()));
        if (item.altTitles() != null) {
            item.altTitles().forEach(a -> names.add(TextNormalizer.normalizeTitle(a)));
        }
        names.remove("");
        return names;
    }

    private final class Group {
        private final List<RemoteTitle> members = new ArrayList<>();
        private final int order;

        Group(int order) {
            this.order = order;
        }

        /** Título principal pesa mais que os alternativos; idioma preferido e múltiplas fontes somam pontos. */
        double score(String query) {
            double main = members.stream().mapToDouble(m -> TextNormalizer.similarity(query, m.title())).max().orElse(0);
            double alt = members.stream()
                    .flatMap(m -> m.altTitles() == null ? java.util.stream.Stream.<String>empty() : m.altTitles().stream())
                    .mapToDouble(a -> TextNormalizer.similarity(query, a)).max().orElse(0);
            boolean preferred = members.stream().anyMatch(m -> m.languages() != null && m.languages().contains(preferredLanguage));
            int bestPriority = members.stream().mapToInt(m -> providerPriority.getOrDefault(m.providerCode(), 100)).min().orElse(100);
            double altScore = alt >= 1.0 ? 0.95 : alt * 0.8; // nome exato em título alternativo supera prefixo no principal
            double score = Math.max(main, altScore) * 100 + (preferred ? 30 : 0) + Math.min(members.size(), 4) * 4 - bestPriority / 20.0;
            return Math.round(score * 10) / 10.0;
        }

        boolean accepts(RemoteTitle item) {
            RemoteTitle first = members.get(0);
            boolean comic = item.type() != null && item.type().isComic();
            boolean firstComic = first.type() != null && first.type().isComic();
            if (comic != firstComic) {
                return false;
            }
            if (comic && item.year() != null && first.year() != null && !Objects.equals(item.year(), first.year())) {
                return false;
            }
            return members.stream().noneMatch(m -> m.providerCode().equals(item.providerCode()));
        }

        void add(RemoteTitle item) {
            members.add(item);
            members.sort(Comparator.comparingInt(m -> providerPriority.getOrDefault(m.providerCode(), 100)));
        }

        AggregatedResult toResult(String query) {
            RemoteTitle primary = members.get(0);
            Set<String> languages = new LinkedHashSet<>();
            List<String> altTitles = new ArrayList<>();
            Map<String, AggregatedResult.SourceRef> sources = new LinkedHashMap<>();
            String description = null;
            String cover = null;
            Integer year = null;
            String status = null;
            String publisher = null;
            for (RemoteTitle m : members) {
                if (m.languages() != null) {
                    languages.addAll(m.languages());
                }
                if (m.altTitles() != null) {
                    m.altTitles().stream().filter(a -> !altTitles.contains(a) && !a.equals(primary.title())).forEach(altTitles::add);
                }
                description = description == null ? m.description() : description;
                cover = cover == null ? m.coverUrl() : cover;
                year = year == null ? m.year() : year;
                status = status == null ? m.status() : status;
                publisher = publisher == null ? m.publisher() : publisher;
                sources.put(m.providerCode() + "|" + m.externalId(), new AggregatedResult.SourceRef(m.providerCode(),
                        m.providerName(), m.externalId(), m.title(), m.url(), m.languages(), m.coverUrl(), false));
            }
            boolean preferred = languages.contains(preferredLanguage);
            String key = primary.providerCode() + ":" + primary.externalId();
            return new AggregatedResult(key, primary.title(), altTitles.stream().limit(6).toList(), description, cover,
                    primary.type(), languages, preferred, year, status, publisher, List.copyOf(sources.values()),
                    score(query), null);
        }
    }
}
