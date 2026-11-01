package com.mangaverse.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "mangaverse")
public record AppProperties(Admin admin, Storage storage, Http http, Providers providers) {

    public record Admin(String username, String password) {
    }

    public record Storage(List<String> allowedRoots, String libraryDir, String dataDir, String cbrExtractor) {
    }

    public record Http(String userAgent, Duration connectTimeout, Duration responseTimeout, int maxRetries) {
    }

    public record Providers(MangaDex mangadex, ComicVine comicvine, Suwayomi suwayomi) {
    }

    public record MangaDex(boolean enabled, String baseUrl, String uploadsUrl, String reportUrl, double requestsPerSecond) {
    }

    public record ComicVine(boolean enabled, String baseUrl, String apiKey, double requestsPerSecond) {
        public boolean configured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }

    public record Suwayomi(boolean enabled, String baseUrl, String username, String password,
                           String defaultExtensionStore, double requestsPerSecond, int maxSourcesPerSearch) {
    }
}
