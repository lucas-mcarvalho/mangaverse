package com.mangaverse.content;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mangaverse.common.StringListConverter;
import com.mangaverse.common.TextNormalizer;
import com.mangaverse.provider.spi.ContentType;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "contents")
public class Content {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(nullable = false, length = 500)
    private String normalizedTitle;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType type;

    private String description;

    @Convert(converter = StringListConverter.class)
    private List<String> authors = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    private List<String> artists = new ArrayList<>();

    private String publisher;

    @Convert(converter = StringListConverter.class)
    private List<String> genres = new ArrayList<>();

    @Convert(converter = StringListConverter.class)
    private List<String> characters = new ArrayList<>();

    private String language;
    private String publicationStatus;
    private String coverUrl;
    private Integer year;
    private String localPath;
    private Instant createdAt;
    private Instant updatedAt;

    protected Content() {
    }

    public Content(String title, ContentType type) {
        setTitle(title);
        this.type = type;
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
    public String getTitle() { return title; }
    public void setTitle(String title) {
        String t = title == null || title.isBlank() ? "(sem título)" : title.trim();
        this.title = t.length() > 500 ? t.substring(0, 500) : t;
        this.normalizedTitle = TextNormalizer.normalizeTitle(this.title);
    }
    public String getNormalizedTitle() { return normalizedTitle; }
    public ContentType getType() { return type; }
    public void setType(ContentType type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<String> getAuthors() { return authors; }
    public void setAuthors(List<String> authors) { this.authors = authors == null ? new ArrayList<>() : new ArrayList<>(authors); }
    public List<String> getArtists() { return artists; }
    public void setArtists(List<String> artists) { this.artists = artists == null ? new ArrayList<>() : new ArrayList<>(artists); }
    public String getPublisher() { return publisher; }
    public void setPublisher(String publisher) { this.publisher = publisher; }
    public List<String> getGenres() { return genres; }
    public void setGenres(List<String> genres) { this.genres = genres == null ? new ArrayList<>() : new ArrayList<>(genres); }
    public List<String> getCharacters() { return characters; }
    public void setCharacters(List<String> characters) { this.characters = characters == null ? new ArrayList<>() : new ArrayList<>(characters); }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getPublicationStatus() { return publicationStatus; }
    public void setPublicationStatus(String publicationStatus) { this.publicationStatus = publicationStatus; }
    public String getCoverUrl() { return coverUrl; }
    public void setCoverUrl(String coverUrl) { this.coverUrl = coverUrl; }
    public Integer getYear() { return year; }
    public void setYear(Integer year) { this.year = year; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
