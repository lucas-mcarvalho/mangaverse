package com.mangaverse.provider;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
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
@Table(name = "providers")
public class Provider {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 120)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ProviderType type;

    private boolean enabled = true;
    private int priority = 100;
    private String language;
    private String externalRef;
    private String iconUrl;
    private boolean supportsSearch = true;
    private boolean supportsDownload;
    private boolean metadataOnly;
    private String lastStatus = "UNKNOWN";
    private String lastError;
    private Instant lastCheckedAt;
    private String defaultContentType;
    private Instant createdAt;
    private Instant updatedAt;

    protected Provider() {
    }

    public Provider(String code, String name, ProviderType type) {
        this.code = code;
        this.name = name;
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
    public String getCode() { return code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public ProviderType getType() { return type; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public String getExternalRef() { return externalRef; }
    public void setExternalRef(String externalRef) { this.externalRef = externalRef; }
    public String getIconUrl() { return iconUrl; }
    public void setIconUrl(String iconUrl) { this.iconUrl = iconUrl; }
    public boolean isSupportsSearch() { return supportsSearch; }
    public void setSupportsSearch(boolean supportsSearch) { this.supportsSearch = supportsSearch; }
    public boolean isSupportsDownload() { return supportsDownload; }
    public void setSupportsDownload(boolean supportsDownload) { this.supportsDownload = supportsDownload; }
    public boolean isMetadataOnly() { return metadataOnly; }
    public void setMetadataOnly(boolean metadataOnly) { this.metadataOnly = metadataOnly; }
    public String getLastStatus() { return lastStatus; }
    public void setLastStatus(String lastStatus) { this.lastStatus = lastStatus; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getLastCheckedAt() { return lastCheckedAt; }
    public String getDefaultContentType() { return defaultContentType; }
    public void setDefaultContentType(String defaultContentType) { this.defaultContentType = defaultContentType; }

    /** Tipo imposto pelo usuário (ou pelo padrão conhecido da extensão); vazio = deduzir pelos gêneros. */
    public java.util.Optional<com.mangaverse.provider.spi.ContentType> forcedType() {
        if (defaultContentType == null || "AUTO".equals(defaultContentType)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(com.mangaverse.provider.spi.ContentType.valueOf(defaultContentType));
    }
    public void setLastCheckedAt(Instant lastCheckedAt) { this.lastCheckedAt = lastCheckedAt; }
}
