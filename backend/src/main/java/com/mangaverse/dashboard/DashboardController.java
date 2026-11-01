package com.mangaverse.dashboard;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.content.ChapterRepository;
import com.mangaverse.content.Covers;
import com.mangaverse.download.DownloadService;
import com.mangaverse.library.LibraryService;
import com.mangaverse.library.LibraryViews;
import com.mangaverse.monitor.UpdateHistory;
import com.mangaverse.monitor.UpdateHistoryRepository;
import com.mangaverse.reader.ReadingProgress;
import com.mangaverse.reader.ReadingProgressRepository;
import com.mangaverse.user.AppUser;
import com.mangaverse.user.CurrentUser;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final CurrentUser currentUser;
    private final ReadingProgressRepository progress;
    private final LibraryService library;
    private final UpdateHistoryRepository history;
    private final DownloadService downloads;
    private final ChapterRepository chapters;

    public DashboardController(CurrentUser currentUser, ReadingProgressRepository progress, LibraryService library,
                               UpdateHistoryRepository history, DownloadService downloads, ChapterRepository chapters) {
        this.currentUser = currentUser;
        this.progress = progress;
        this.library = library;
        this.history = history;
        this.downloads = downloads;
        this.chapters = chapters;
    }

    public record ContinueItem(String contentId, String title, String coverUrl, String chapterId, String chapterLabel,
                               int page, int totalPages, boolean completed, String type) {
    }

    public record NewChapterItem(String id, String contentId, String contentTitle, String coverUrl, String chapterId,
                                 String message, java.time.Instant createdAt) {
    }

    public record Stats(int titles, int mangas, int comics, int reading, int completed, int favorites, int monitored,
                        long chaptersDownloaded, int unreadChapters) {
    }

    public record Dashboard(List<ContinueItem> continueReading, List<LibraryViews.LibraryItem> recentlyAdded,
                            List<NewChapterItem> latestChapters, List<DownloadService.DownloadView> activeDownloads,
                            List<LibraryViews.LibraryItem> favoriteMangas, List<LibraryViews.LibraryItem> favoriteComics,
                            Stats stats) {
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Dashboard get() {
        AppUser user = currentUser.get();
        List<LibraryViews.LibraryItem> all = library.list(user, new LibraryService.Filter(null, null, null, null, null, null));

        Map<UUID, ContinueItem> continueMap = new LinkedHashMap<>();
        for (ReadingProgress p : progress.findRecent(user.getId(), PageRequest.of(0, 60))) {
            UUID contentId = p.getContent().getId();
            if (continueMap.containsKey(contentId) || continueMap.size() >= 12) {
                continue;
            }
            var content = p.getContent();
            continueMap.put(contentId, new ContinueItem(contentId.toString(), content.getTitle(),
                    Covers.resolve(content.getCoverUrl(), contentId), p.getChapter().getId().toString(),
                    p.getChapter().displayLabel(content.getType().isComic()), p.getPage(), p.getTotalPages(),
                    p.isCompleted(), content.getType().name()));
        }
        Set<String> seenChapters = new LinkedHashSet<>();
        List<NewChapterItem> latest = history.findRecentByType(UpdateHistory.EventType.NEW_CHAPTER, PageRequest.of(0, 30)).stream()
                .filter(h -> h.getChapter() == null || seenChapters.add(h.getChapter().getId().toString()))
                .limit(15)
                .map(h -> new NewChapterItem(h.getId().toString(), h.getContent().getId().toString(), h.getContent().getTitle(),
                        Covers.resolve(h.getContent().getCoverUrl(), h.getContent().getId()),
                        h.getChapter() == null ? null : h.getChapter().getId().toString(), h.getMessage(), h.getCreatedAt()))
                .toList();
        List<DownloadService.DownloadView> active = downloads.list(50).stream()
                .filter(d -> d.status().equals("DOWNLOADING") || d.status().equals("QUEUED") || d.status().equals("PAUSED"))
                .limit(8).toList();

        Stats stats = new Stats(all.size(),
                (int) all.stream().filter(i -> !i.type().isComic()).count(),
                (int) all.stream().filter(i -> i.type().isComic()).count(),
                (int) all.stream().filter(i -> i.status().equals("READING")).count(),
                (int) all.stream().filter(i -> i.status().equals("COMPLETED")).count(),
                (int) all.stream().filter(LibraryViews.LibraryItem::favorite).count(),
                (int) all.stream().filter(LibraryViews.LibraryItem::monitored).count(),
                chapters.countByLocalPathIsNotNull(),
                all.stream().mapToInt(LibraryViews.LibraryItem::unreadChapters).sum());

        return new Dashboard(List.copyOf(continueMap.values()), all.stream().limit(12).toList(), latest, active,
                all.stream().filter(i -> i.favorite() && !i.type().isComic()).limit(12).toList(),
                all.stream().filter(i -> i.favorite() && i.type().isComic()).limit(12).toList(), stats);
    }
}
