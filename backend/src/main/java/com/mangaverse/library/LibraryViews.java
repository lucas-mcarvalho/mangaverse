package com.mangaverse.library;

import java.time.Instant;

import com.mangaverse.provider.spi.ContentType;

public final class LibraryViews {

    private LibraryViews() {
    }

    public record LibraryItem(String entryId, String contentId, String title, ContentType type, String coverUrl,
                              String status, boolean favorite, boolean monitored, boolean autoDownload,
                              String categoryId, String categoryName, Instant addedAt, Instant lastReadAt,
                              int totalChapters, int readChapters, int unreadChapters, int downloadedChapters,
                              int activeDownloads, String lastReadChapterLabel, String lastReadChapterId,
                              int progressPercent, boolean local) {
    }

    public record CategoryView(String id, String name) {
    }
}
