package com.mangaverse.content;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangaverse.common.ApiException;
import com.mangaverse.download.DownloadRepository;
import com.mangaverse.library.LibraryEntry;
import com.mangaverse.library.LibraryEntryRepository;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.reader.ReadingProgress;
import com.mangaverse.reader.ReadingProgressRepository;
import com.mangaverse.user.AppUser;

@Service
public class ContentQueryService {

    private final ContentRepository contents;
    private final ContentSourceRepository sources;
    private final ChapterRepository chapters;
    private final ReadingProgressRepository progress;
    private final LibraryEntryRepository library;
    private final DownloadRepository downloads;
    private final ProviderRegistry registry;

    public ContentQueryService(ContentRepository contents, ContentSourceRepository sources, ChapterRepository chapters,
                               ReadingProgressRepository progress, LibraryEntryRepository library,
                               DownloadRepository downloads, ProviderRegistry registry) {
        this.contents = contents;
        this.sources = sources;
        this.chapters = chapters;
        this.progress = progress;
        this.library = library;
        this.downloads = downloads;
        this.registry = registry;
    }

    @Transactional(readOnly = true)
    public ContentViews.ContentView view(UUID contentId, AppUser user) {
        Content c = contents.findById(contentId).orElseThrow(() -> ApiException.notFound("Conteúdo não encontrado."));
        List<ContentViews.SourceView> sourceViews = sources.findByContentId(contentId).stream().map(s -> {
            ContentProvider impl = registry.find(s.getProvider().getCode()).orElse(null);
            return new ContentViews.SourceView(s.getId().toString(), s.getProvider().getCode(), s.getProvider().getName(),
                    s.getProvider().getType().name(), s.getExternalId(), s.getUrl(), s.getLanguage(), s.getLastSyncedAt(),
                    impl == null ? null : impl.capabilities());
        }).toList();

        Map<UUID, ReadingProgress> progressByChapter = new HashMap<>();
        for (ReadingProgress p : progress.findByUserIdAndContentId(user.getId(), contentId)) {
            progressByChapter.put(p.getChapter().getId(), p);
        }
        Map<UUID, String> downloadStatus = new HashMap<>();
        for (Object[] row : downloads.findStatusesForContent(contentId)) {
            downloadStatus.put((UUID) row[0], row[1].toString());
        }
        boolean comic = c.getType().isComic();
        List<ContentViews.ChapterView> chapterViews = chapters.findByContentOrdered(contentId).stream().map(ch -> {
            ReadingProgress p = progressByChapter.get(ch.getId());
            return new ContentViews.ChapterView(ch.getId().toString(), ch.getExternalId(), ch.getNumber(), ch.getNumberLabel(),
                    ch.displayLabel(comic), ch.getVolume(), ch.getTitle(), ch.getLanguage(), ch.getScanlator(),
                    ch.getPublishedAt(), ch.getPageCount(), ch.isReadable() || ch.isLocal(), ch.isDownloadable() && !ch.isLocal(),
                    ch.getExternalUrl(), ch.isLocal(), ch.getFileSize(),
                    ch.getSource() == null ? "local" : ch.getSource().getProvider().getCode(),
                    ch.getSource() == null ? null : ch.getSource().getId().toString(),
                    p != null && p.isCompleted(), p == null ? null : p.getPage(),
                    downloadStatus.get(ch.getId()), ch.getCreatedAt());
        }).toList();

        LibraryEntry entry = library.findByUserIdAndContentId(user.getId(), contentId).orElse(null);
        ContentViews.LibraryInfo info = entry == null ? null : new ContentViews.LibraryInfo(entry.getId().toString(),
                entry.getStatus().name(), entry.isFavorite(), entry.isMonitored(), entry.isAutoDownload(),
                entry.getCategory() == null ? null : entry.getCategory().getId().toString(), entry.getAddedAt(),
                entry.getLastCheckedAt());
        int read = (int) chapterViews.stream().filter(ContentViews.ChapterView::read).count();
        int downloaded = (int) chapterViews.stream().filter(ContentViews.ChapterView::downloaded).count();
        int officialOnly = officialOnlyCount(chapterViews);
        return new ContentViews.ContentView(c.getId().toString(), c.getTitle(), c.getType(), c.getDescription(),
                c.getAuthors(), c.getArtists(), c.getPublisher(), c.getGenres(), c.getCharacters(), c.getLanguage(),
                c.getPublicationStatus(), Covers.resolve(c.getCoverUrl(), c.getId()), c.getYear(), c.getLocalPath() != null,
                c.getLocalPath(), sourceViews, chapterViews, info, chapterViews.size(), read, downloaded, officialOnly);
    }

    /** Números de capítulo que só existem como link para o site oficial (nenhuma versão legível em fonte alguma). */
    static int officialOnlyCount(List<ContentViews.ChapterView> views) {
        Map<String, List<ContentViews.ChapterView>> byNumber = new HashMap<>();
        for (ContentViews.ChapterView v : views) {
            if (v.number() != null) {
                byNumber.computeIfAbsent(v.number().stripTrailingZeros().toPlainString(), k -> new java.util.ArrayList<>()).add(v);
            }
        }
        return (int) byNumber.values().stream()
                .filter(list -> list.stream().noneMatch(v -> v.readable() || v.downloaded()))
                .filter(list -> list.stream().anyMatch(v -> v.externalUrl() != null))
                .count();
    }
}
