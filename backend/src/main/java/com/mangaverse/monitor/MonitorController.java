package com.mangaverse.monitor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.common.ApiException;
import com.mangaverse.content.Covers;
import com.mangaverse.library.LibraryEntryRepository;
import com.mangaverse.settings.SettingsService;
import com.mangaverse.user.CurrentUser;

@RestController
@RequestMapping("/api")
public class MonitorController {

    private final MonitorService monitor;
    private final UpdateHistoryRepository history;
    private final LibraryEntryRepository library;
    private final CurrentUser currentUser;
    private final SettingsService settings;

    public MonitorController(MonitorService monitor, UpdateHistoryRepository history, LibraryEntryRepository library,
                             CurrentUser currentUser, SettingsService settings) {
        this.monitor = monitor;
        this.history = history;
        this.library = library;
        this.currentUser = currentUser;
        this.settings = settings;
    }

    public record MonitorStatus(boolean enabled, boolean running, int intervalMinutes, Instant lastRunAt) {
    }

    @GetMapping("/monitor")
    public MonitorStatus status() {
        return new MonitorStatus(settings.monitorEnabled(), monitor.isRunning(), settings.monitorIntervalMinutes(),
                settings.monitorLastRunAt());
    }

    /** Dispara a verificação em segundo plano. */
    @PostMapping("/monitor/run")
    public ResponseEntity<Map<String, String>> runNow() {
        if (monitor.isRunning()) {
            throw ApiException.conflict("Uma verificação já está em andamento.");
        }
        CompletableFuture.runAsync(() -> {
            try {
                monitor.runAll();
            } catch (ApiException ignored) {
                // já em execução
            }
        });
        return ResponseEntity.accepted().body(Map.of("status", "started"));
    }

    @PostMapping("/library/{entryId}/check")
    public MonitorService.CheckResult checkEntry(@PathVariable UUID entryId) {
        var entry = library.findForUser(entryId, currentUser.get().getId())
                .orElseThrow(() -> ApiException.notFound("Item da biblioteca não encontrado."));
        return monitor.checkContent(entry.getContent().getId(), entry.isAutoDownload());
    }

    public record NotificationView(String id, String type, String message, boolean seen, Instant createdAt,
                                   String contentId, String contentTitle, String coverUrl, String chapterId) {
    }

    public record Notifications(long unseen, List<NotificationView> items) {
    }

    @GetMapping("/notifications")
    @Transactional(readOnly = true)
    public Notifications notifications(@RequestParam(defaultValue = "50") int limit) {
        List<NotificationView> items = history.findRecent(PageRequest.of(0, Math.max(1, Math.min(limit, 200)))).stream()
                .map(h -> new NotificationView(h.getId().toString(), h.getEventType().name(), h.getMessage(), h.isSeen(),
                        h.getCreatedAt(), h.getContent().getId().toString(), h.getContent().getTitle(),
                        Covers.resolve(h.getContent().getCoverUrl(), h.getContent().getId()),
                        h.getChapter() == null ? null : h.getChapter().getId().toString()))
                .toList();
        return new Notifications(history.countBySeenFalse(), items);
    }

    @PostMapping("/notifications/seen")
    @Transactional
    public Map<String, Integer> markSeen() {
        return Map.of("updated", history.markAllSeen());
    }
}
