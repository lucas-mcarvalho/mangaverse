package com.mangaverse.library;

import java.time.Instant;
import java.util.UUID;

import com.mangaverse.content.Content;
import com.mangaverse.user.AppUser;

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
import jakarta.persistence.Table;

@Entity
@Table(name = "library_entries")
public class LibraryEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id")
    private Content content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    @Enumerated(EnumType.STRING)
    private LibraryStatus status = LibraryStatus.PLAN_TO_READ;

    private boolean favorite;
    private boolean monitored;
    private boolean autoDownload;
    private Instant addedAt;
    private Instant lastReadAt;
    private Instant lastCheckedAt;

    protected LibraryEntry() {
    }

    public LibraryEntry(AppUser user, Content content) {
        this.user = user;
        this.content = content;
    }

    @PrePersist
    void prePersist() {
        if (addedAt == null) {
            addedAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public AppUser getUser() { return user; }
    public Content getContent() { return content; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public LibraryStatus getStatus() { return status; }
    public void setStatus(LibraryStatus status) { this.status = status; }
    public boolean isFavorite() { return favorite; }
    public void setFavorite(boolean favorite) { this.favorite = favorite; }
    public boolean isMonitored() { return monitored; }
    public void setMonitored(boolean monitored) { this.monitored = monitored; }
    public boolean isAutoDownload() { return autoDownload; }
    public void setAutoDownload(boolean autoDownload) { this.autoDownload = autoDownload; }
    public Instant getAddedAt() { return addedAt; }
    public Instant getLastReadAt() { return lastReadAt; }
    public void setLastReadAt(Instant lastReadAt) { this.lastReadAt = lastReadAt; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
}
