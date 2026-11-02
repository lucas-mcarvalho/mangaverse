package com.mangaverse.settings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangaverse.common.ApiException;
import com.mangaverse.config.AppProperties;
import com.mangaverse.provider.mangadex.MangaDexProvider;
import com.mangaverse.storage.PathGuard;
import com.mangaverse.storage.archive.ArchiveService;

/** Configurações persistidas no banco (tabela app_settings) com valores padrão vindos do ambiente. */
@Service
public class SettingsService {

    static final String MANGA_PATH = "library.mangaPath";
    static final String COMIC_PATH = "library.comicPath";
    static final String MAX_CONCURRENT = "downloads.maxConcurrent";
    static final String AUTO_DOWNLOAD = "downloads.autoDownloadEnabled";
    static final String MONITOR_ENABLED = "monitor.enabled";
    static final String MONITOR_INTERVAL = "monitor.intervalMinutes";
    static final String MONITOR_LAST_RUN = "monitor.lastRunAt";
    static final String LANGUAGES = "search.languages";
    static final String CONTENT_RATINGS = "mangadex.contentRatings";
    static final String TRANSLATION_THREADS = "translation.threads";

    public static final int MIN_MONITOR_INTERVAL = 30;

    private final AppSettingRepository repository;
    private final AppProperties properties;
    private final PathGuard pathGuard;
    private final MangaDexProvider mangaDex;
    private final ArchiveService archiveService;

    public SettingsService(AppSettingRepository repository, AppProperties properties, PathGuard pathGuard,
                           MangaDexProvider mangaDex, ArchiveService archiveService) {
        this.repository = repository;
        this.properties = properties;
        this.pathGuard = pathGuard;
        this.mangaDex = mangaDex;
        this.archiveService = archiveService;
    }

    public record SettingsView(String mangaLibraryPath, String comicLibraryPath, int maxConcurrentDownloads,
                               boolean autoDownloadEnabled, boolean monitorEnabled, int monitorIntervalMinutes,
                               Instant monitorLastRunAt, List<String> languages, List<String> contentRatings,
                               List<String> allowedRoots, boolean cbrSupported, String cbrTool,
                               boolean comicVineConfigured, String suwayomiUrl, int translationThreads,
                               int availableCpus) {
    }

    public record SettingsUpdate(String mangaLibraryPath, String comicLibraryPath, Integer maxConcurrentDownloads,
                                 Boolean autoDownloadEnabled, Boolean monitorEnabled, Integer monitorIntervalMinutes,
                                 List<String> languages, List<String> contentRatings, Integer translationThreads) {
    }

    @EventListener(ApplicationReadyEvent.class)
    public void applyRuntimeSettings() {
        mangaDex.setContentRatings(contentRatings());
    }

    @Transactional(readOnly = true)
    public SettingsView view() {
        return new SettingsView(mangaLibraryPath().toString(), comicLibraryPath().toString(), maxConcurrentDownloads(),
                autoDownloadEnabled(), monitorEnabled(), monitorIntervalMinutes(), monitorLastRunAt(), languages(),
                contentRatings(), pathGuard.roots().stream().map(Path::toString).toList(),
                archiveService.cbrSupported(), archiveService.cbrTool(),
                properties.providers().comicvine().configured(), properties.providers().suwayomi().baseUrl(),
                translationThreads(), availableCpus());
    }

    @Transactional
    public SettingsView update(SettingsUpdate u) {
        if (u.mangaLibraryPath() != null) {
            put(MANGA_PATH, prepareDirectory(u.mangaLibraryPath()).toString());
        }
        if (u.comicLibraryPath() != null) {
            put(COMIC_PATH, prepareDirectory(u.comicLibraryPath()).toString());
        }
        if (u.maxConcurrentDownloads() != null) {
            if (u.maxConcurrentDownloads() < 1 || u.maxConcurrentDownloads() > 6) {
                throw ApiException.badRequest("Downloads simultâneos deve estar entre 1 e 6.");
            }
            put(MAX_CONCURRENT, String.valueOf(u.maxConcurrentDownloads()));
        }
        if (u.autoDownloadEnabled() != null) {
            put(AUTO_DOWNLOAD, String.valueOf(u.autoDownloadEnabled()));
        }
        if (u.monitorEnabled() != null) {
            put(MONITOR_ENABLED, String.valueOf(u.monitorEnabled()));
        }
        if (u.monitorIntervalMinutes() != null) {
            if (u.monitorIntervalMinutes() < MIN_MONITOR_INTERVAL || u.monitorIntervalMinutes() > 10080) {
                throw ApiException.badRequest("Intervalo de verificação deve ficar entre " + MIN_MONITOR_INTERVAL + " minutos e 7 dias.");
            }
            put(MONITOR_INTERVAL, String.valueOf(u.monitorIntervalMinutes()));
        }
        if (u.languages() != null) {
            List<String> langs = cleanList(u.languages());
            if (langs.isEmpty() || langs.stream().anyMatch(l -> !l.matches("[a-z]{2}(-[a-z]{2})?"))) {
                throw ApiException.badRequest("Idiomas inválidos. Use códigos como pt-br, en, es-la.");
            }
            put(LANGUAGES, String.join(",", langs));
        }
        if (u.contentRatings() != null) {
            List<String> ratings = cleanList(u.contentRatings());
            if (ratings.isEmpty() || !Set.of("safe", "suggestive", "erotica", "pornographic").containsAll(ratings)) {
                throw ApiException.badRequest("Classificações inválidas.");
            }
            put(CONTENT_RATINGS, String.join(",", ratings));
            mangaDex.setContentRatings(ratings);
        }
        if (u.translationThreads() != null) {
            if (u.translationThreads() < 1 || u.translationThreads() > availableCpus()) {
                throw ApiException.badRequest("Núcleos para tradução deve estar entre 1 e " + availableCpus() + ".");
            }
            put(TRANSLATION_THREADS, String.valueOf(u.translationThreads()));
        }
        return view();
    }

    public Path mangaLibraryPath() {
        return Path.of(get(MANGA_PATH, Path.of(properties.storage().libraryDir(), "Mangas").toString()));
    }

    public Path comicLibraryPath() {
        return Path.of(get(COMIC_PATH, Path.of(properties.storage().libraryDir(), "HQs").toString()));
    }

    public int maxConcurrentDownloads() {
        return parseInt(get(MAX_CONCURRENT, "2"), 2);
    }

    public boolean autoDownloadEnabled() {
        return Boolean.parseBoolean(get(AUTO_DOWNLOAD, "false"));
    }

    public boolean monitorEnabled() {
        return Boolean.parseBoolean(get(MONITOR_ENABLED, "true"));
    }

    public int monitorIntervalMinutes() {
        return Math.max(MIN_MONITOR_INTERVAL, parseInt(get(MONITOR_INTERVAL, "360"), 360));
    }

    /** Threads de CPU que o tradutor usa por página; o padrão deixa o restante da máquina livre. */
    public int translationThreads() {
        int fallback = Math.min(4, availableCpus());
        return Math.max(1, Math.min(availableCpus(), parseInt(get(TRANSLATION_THREADS, String.valueOf(fallback)), fallback)));
    }

    /** Núcleos do host: o backend e o tradutor rodam na mesma máquina, sem limite de CPU no Compose. */
    public int availableCpus() {
        return Runtime.getRuntime().availableProcessors();
    }

    public Instant monitorLastRunAt() {
        String v = get(MONITOR_LAST_RUN, null);
        return v == null ? null : Instant.parse(v);
    }

    @Transactional
    public void markMonitorRun(Instant when) {
        put(MONITOR_LAST_RUN, when.toString());
    }

    /** Idiomas de tradução desejados, em ordem de prioridade (pt-br primeiro por padrão). */
    public List<String> languages() {
        return cleanList(Arrays.asList(get(LANGUAGES, "pt-br,en").split(",")));
    }

    public List<String> contentRatings() {
        return cleanList(Arrays.asList(get(CONTENT_RATINGS, "safe,suggestive").split(",")));
    }

    public String preferredLanguage() {
        List<String> langs = languages();
        return langs.isEmpty() ? "pt-br" : langs.get(0);
    }

    private Path prepareDirectory(String raw) {
        Path p = pathGuard.requireAllowed(raw);
        try {
            Files.createDirectories(p);
        } catch (IOException e) {
            throw ApiException.badRequest("Não foi possível criar o diretório: " + e.getMessage());
        }
        if (!Files.isWritable(p)) {
            throw ApiException.badRequest("Sem permissão de escrita em " + p);
        }
        return p;
    }

    private String get(String key, String defaultValue) {
        return repository.findByKey(key).map(AppSetting::getValue).filter(v -> !v.isBlank()).orElse(defaultValue);
    }

    private void put(String key, String value) {
        AppSetting s = repository.findByKey(key).orElseGet(() -> new AppSetting(key, value));
        s.setValue(value);
        repository.save(s);
    }

    private static List<String> cleanList(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim().toLowerCase(Locale.ROOT));
            }
        }
        return List.copyOf(out);
    }

    private static int parseInt(String v, int def) {
        try {
            return Integer.parseInt(v.trim());
        } catch (RuntimeException e) {
            return def;
        }
    }
}
