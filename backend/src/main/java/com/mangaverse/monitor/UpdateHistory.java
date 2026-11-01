package com.mangaverse.monitor;

import java.time.Instant;
import java.util.UUID;

import com.mangaverse.content.Chapter;
import com.mangaverse.content.Content;
import com.mangaverse.provider.Provider;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "update_history")
public class UpdateHistory {

    public enum EventType { NEW_CHAPTER, CHECK_FAILED, DOWNLOAD_COMPLETED, DOWNLOAD_FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id")
    private Content content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "chapter_id")
    private Chapter chapter;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "provider_id")
    private Provider provider;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    private EventType eventType;

    private String message;
    private boolean seen;
    private Instant createdAt;

    protected UpdateHistory() {
    }

    public UpdateHistory(Content content, Chapter chapter, Provider provider, EventType eventType, String message) {
        this.content = content;
        this.chapter = chapter;
        this.provider = provider;
        this.eventType = eventType;
        this.message = message;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Content getContent() { return content; }
    public Chapter getChapter() { return chapter; }
    public Provider getProvider() { return provider; }
    public EventType getEventType() { return eventType; }
    public String getMessage() { return message; }
    public boolean isSeen() { return seen; }
    public void setSeen(boolean seen) { this.seen = seen; }
    public Instant getCreatedAt() { return createdAt; }
}
