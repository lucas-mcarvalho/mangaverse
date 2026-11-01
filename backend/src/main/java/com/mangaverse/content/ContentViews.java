package com.mangaverse.content;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.ProviderCapabilities;

public final class ContentViews {

    private ContentViews() {
    }

    public record SourceView(String id, String providerCode, String providerName, String providerType,
                             String externalId, String url, String language, Instant lastSyncedAt,
                             ProviderCapabilities capabilities) {
    }

    public record ChapterView(String id, String externalId, BigDecimal number, String numberLabel, String label, String volume, String title,
                              String language, String scanlator, Instant publishedAt, Integer pageCount,
                              boolean readable, boolean downloadable, String externalUrl, boolean downloaded,
                              Long fileSize, String providerCode, String sourceId, boolean read, Integer progressPage,
                              String downloadStatus, Instant createdAt) {
    }

    public record LibraryInfo(String entryId, String status, boolean favorite, boolean monitored, boolean autoDownload,
                              String categoryId, Instant addedAt, Instant lastCheckedAt) {
    }

    public record ContentView(String id, String title, ContentType type, String description, List<String> authors,
                              List<String> artists, String publisher, List<String> genres, List<String> characters,
                              String language, String status, String coverUrl, Integer year, boolean local,
                              String localPath, List<SourceView> sources, List<ChapterView> chapters, LibraryInfo library,
                              int totalChapters, int readChapters, int downloadedChapters, int officialOnlyCount) {
    }
}
