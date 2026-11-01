package com.mangaverse.download;

import java.time.Instant;
import java.util.UUID;

import com.mangaverse.content.Chapter;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "downloads")
public class Download {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "chapter_id")
    private Chapter chapter;

    @Enumerated(EnumType.STRING)
    private DownloadStatus status = DownloadStatus.QUEUED;

    private int progress;
    private int totalPages;
    private int downloadedPages;
    private long bytesDownloaded;
    private long speedBps;
    private String errorMessage;
    private int attempts;
    private String filePath;
    private boolean autoTriggered;
    private Instant createdAt;
    private Instant startedAt;
    private Instant finishedAt;
    private Instant updatedAt;

    protected Download() {
    }

    public Download(Chapter chapter, boolean autoTriggered) {
        this.chapter = chapter;
        this.autoTriggered = autoTriggered;
    }

    @PrePersist
    void prePersist() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Chapter getChapter() { return chapter; }
    public DownloadStatus getStatus() { return status; }
    public void setStatus(DownloadStatus status) { this.status = status; }
    public int getProgress() { return progress; }
    public void setProgress(int progress) { this.progress = Math.max(0, Math.min(100, progress)); }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
    public int getDownloadedPages() { return downloadedPages; }
    public void setDownloadedPages(int downloadedPages) { this.downloadedPages = downloadedPages; }
    public long getBytesDownloaded() { return bytesDownloaded; }
    public void setBytesDownloaded(long bytesDownloaded) { this.bytesDownloaded = bytesDownloaded; }
    public long getSpeedBps() { return speedBps; }
    public void setSpeedBps(long speedBps) { this.speedBps = speedBps; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage == null ? null : errorMessage.length() > 2000 ? errorMessage.substring(0, 2000) : errorMessage; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public boolean isAutoTriggered() { return autoTriggered; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
