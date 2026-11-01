package com.mangaverse.content;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

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
@Table(name = "chapters")
public class Chapter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "content_id")
    private Content content;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "content_source_id")
    private ContentSource source;

    private String externalId;
    private BigDecimal number;
    private String numberLabel;
    private String volume;
    private String title;
    private String language;
    private String scanlator;
    private Instant publishedAt;
    private Integer pageCount;
    private boolean readable = true;
    private boolean downloadable;
    private String externalUrl;
    private String localPath;
    private Long fileSize;
    private String checksum;
    private Instant downloadedAt;
    private Instant createdAt;

    protected Chapter() {
    }

    public Chapter(Content content, ContentSource source, String externalId) {
        this.content = content;
        this.source = source;
        this.externalId = externalId;
    }

    @PrePersist
    void prePersist() {
        createdAt = Instant.now();
    }

    /** Associa um capítulo local a uma fonte remota (mesmo capítulo, arquivo já presente). */
    public void linkTo(ContentSource source, String externalId) {
        this.source = source;
        this.externalId = externalId;
    }

    public boolean isLocal() {
        return localPath != null;
    }

    /** Rótulo amigável: "Capítulo 12" / "Edição 3". */
    public String displayLabel(boolean comic) {
        String prefix = comic ? "Edição" : "Capítulo";
        String n = numberLabel != null ? numberLabel : number != null ? number.stripTrailingZeros().toPlainString() : null;
        if (n == null) {
            return title != null ? title : prefix;
        }
        return prefix + " " + n;
    }

    public UUID getId() { return id; }
    public Content getContent() { return content; }
    public ContentSource getSource() { return source; }
    public String getExternalId() { return externalId; }
    public BigDecimal getNumber() { return number; }
    public void setNumber(BigDecimal number) { this.number = number; }
    public String getNumberLabel() { return numberLabel; }
    public void setNumberLabel(String numberLabel) { this.numberLabel = numberLabel; }
    public String getVolume() { return volume; }
    public void setVolume(String volume) { this.volume = volume; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title == null ? null : title.length() > 500 ? title.substring(0, 500) : title; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getScanlator() { return scanlator; }
    public void setScanlator(String scanlator) { this.scanlator = scanlator == null ? null : scanlator.length() > 300 ? scanlator.substring(0, 300) : scanlator; }
    public Instant getPublishedAt() { return publishedAt; }
    public void setPublishedAt(Instant publishedAt) { this.publishedAt = publishedAt; }
    public Integer getPageCount() { return pageCount; }
    public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
    public boolean isReadable() { return readable; }
    public void setReadable(boolean readable) { this.readable = readable; }
    public boolean isDownloadable() { return downloadable; }
    public void setDownloadable(boolean downloadable) { this.downloadable = downloadable; }
    public String getExternalUrl() { return externalUrl; }
    public void setExternalUrl(String externalUrl) { this.externalUrl = externalUrl; }
    public String getLocalPath() { return localPath; }
    public void setLocalPath(String localPath) { this.localPath = localPath; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public String getChecksum() { return checksum; }
    public void setChecksum(String checksum) { this.checksum = checksum; }
    public Instant getDownloadedAt() { return downloadedAt; }
    public void setDownloadedAt(Instant downloadedAt) { this.downloadedAt = downloadedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
