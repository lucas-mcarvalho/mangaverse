package com.mangaverse.monitor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mangaverse.common.ApiException;
import com.mangaverse.content.Chapter;
import com.mangaverse.content.ContentRepository;
import com.mangaverse.content.ContentService;
import com.mangaverse.content.ContentSource;
import com.mangaverse.content.ContentSourceRepository;
import com.mangaverse.download.DownloadService;
import com.mangaverse.library.LibraryEntry;
import com.mangaverse.library.LibraryEntryRepository;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.settings.SettingsService;

/**
 * Monitora títulos marcados com "Monitorar novas edições": consulta as fontes periodicamente,
 * registra novos capítulos, gera notificações e, se autorizado, enfileira downloads automáticos.
 */
@Service
public class MonitorService {

    private static final Logger log = LoggerFactory.getLogger(MonitorService.class);

    private final LibraryEntryRepository library;
    private final ContentSourceRepository sources;
    private final ContentRepository contents;
    private final ContentService contentService;
    private final DownloadService downloads;
    private final UpdateHistoryRepository history;
    private final SettingsService settings;
    private final ProviderService providerService;
    private final TransactionTemplate tx;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public MonitorService(LibraryEntryRepository library, ContentSourceRepository sources, ContentRepository contents,
                          ContentService contentService, DownloadService downloads, UpdateHistoryRepository history,
                          SettingsService settings, ProviderService providerService, PlatformTransactionManager txManager) {
        this.library = library;
        this.sources = sources;
        this.contents = contents;
        this.contentService = contentService;
        this.downloads = downloads;
        this.history = history;
        this.settings = settings;
        this.providerService = providerService;
        this.tx = new TransactionTemplate(txManager);
    }

    public record CheckResult(int contentsChecked, int newChapters, int autoQueued, List<String> errors) {
    }

    /** Verificado a cada minuto; executa somente quando o intervalo configurado já passou. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    public void tick() {
        if (!settings.monitorEnabled()) {
            return;
        }
        Instant last = settings.monitorLastRunAt();
        if (last != null && Duration.between(last, Instant.now()).toMinutes() < settings.monitorIntervalMinutes()) {
            return;
        }
        runAll();
    }

    public CheckResult runAll() {
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("Uma verificação já está em andamento.");
        }
        try {
            settings.markMonitorRun(Instant.now());
            List<LibraryEntry> monitored = tx.execute(s -> library.findMonitored());
            Map<UUID, Boolean> contentsToCheck = new LinkedHashMap<>();
            for (LibraryEntry e : monitored) {
                contentsToCheck.merge(e.getContent().getId(), e.isAutoDownload(), Boolean::logicalOr);
            }
            int newChapters = 0;
            int queued = 0;
            List<String> errors = new ArrayList<>();
            for (Map.Entry<UUID, Boolean> entry : contentsToCheck.entrySet()) {
                CheckResult r = checkContent(entry.getKey(), entry.getValue());
                newChapters += r.newChapters();
                queued += r.autoQueued();
                errors.addAll(r.errors());
            }
            log.info("Monitoramento concluído: {} título(s), {} novo(s) capítulo(s), {} download(s) automático(s)",
                    contentsToCheck.size(), newChapters, queued);
            return new CheckResult(contentsToCheck.size(), newChapters, queued, errors);
        } finally {
            running.set(false);
        }
    }

    public CheckResult checkContent(UUID contentId, boolean autoDownload) {
        List<ContentSource> list = sources.findByContentId(contentId);
        String preferred = settings.preferredLanguage();
        boolean autoAllowed = autoDownload && settings.autoDownloadEnabled();
        int created = 0;
        List<UUID> toQueue = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (ContentSource source : list) {
            String code = source.getProvider().getCode();
            try {
                List<Chapter> fresh = contentService.syncChapters(source.getId(), true);
                created += fresh.size();
                if (!fresh.isEmpty()) {
                    recordNew(contentId, source, fresh);
                }
                if (autoAllowed) {
                    fresh.stream()
                            .filter(Chapter::isDownloadable)
                            .filter(c -> c.getLanguage() == null || Objects.equals(c.getLanguage(), preferred))
                            .forEach(c -> toQueue.add(c.getId()));
                }
                providerService.recordStatus(code, ProviderHealth.online("Verificação concluída"));
            } catch (RuntimeException e) {
                String message = source.getProvider().getName() + ": " + e.getMessage();
                errors.add(message);
                log.warn("Falha ao verificar atualizações de {} em {}: {}", contentId, code, e.getMessage());
                recordFailure(contentId, source, e.getMessage());
            }
        }
        int queued = toQueue.isEmpty() ? 0 : downloads.enqueue(toQueue, true).queued();
        tx.executeWithoutResult(s -> library.findAll().stream()
                .filter(e -> e.getContent().getId().equals(contentId))
                .forEach(e -> e.setLastCheckedAt(Instant.now())));
        return new CheckResult(1, created, queued, errors);
    }

    private void recordNew(UUID contentId, ContentSource source, List<Chapter> fresh) {
        tx.executeWithoutResult(s -> {
            var content = contents.getReferenceById(contentId);
            boolean comic = contents.findById(contentId).map(c -> c.getType().isComic()).orElse(false);
            String title = contents.findById(contentId).map(c -> c.getTitle()).orElse("");
            for (Chapter c : fresh) {
                history.save(new UpdateHistory(content, c, source.getProvider(), UpdateHistory.EventType.NEW_CHAPTER,
                        (comic ? "Nova edição: " : "Novo capítulo: ") + c.displayLabel(comic) + " de " + title
                                + (c.getLanguage() != null ? " [" + c.getLanguage() + "]" : "")
                                + " — " + source.getProvider().getName()));
            }
        });
    }

    /** Registra no máximo uma falha por título a cada 24 h para não poluir as notificações. */
    private void recordFailure(UUID contentId, ContentSource source, String message) {
        tx.executeWithoutResult(s -> {
            boolean recent = history.findRecentByType(UpdateHistory.EventType.CHECK_FAILED, PageRequest.of(0, 50)).stream()
                    .anyMatch(h -> h.getContent().getId().equals(contentId)
                            && h.getCreatedAt().isAfter(Instant.now().minus(Duration.ofHours(24))));
            if (!recent) {
                history.save(new UpdateHistory(contents.getReferenceById(contentId), null, source.getProvider(),
                        UpdateHistory.EventType.CHECK_FAILED, "Falha ao verificar " + source.getProvider().getName() + ": " + message));
            }
        });
    }

    public boolean isRunning() {
        return running.get();
    }
}
