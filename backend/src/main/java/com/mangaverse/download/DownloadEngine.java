package com.mangaverse.download;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mangaverse.config.AppProperties;
import com.mangaverse.content.Chapter;
import com.mangaverse.content.ChapterRepository;
import com.mangaverse.monitor.UpdateHistory;
import com.mangaverse.monitor.UpdateHistoryRepository;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.FetchedImage;
import com.mangaverse.provider.spi.PageRef;
import com.mangaverse.settings.SettingsService;
import com.mangaverse.storage.archive.ImageTypes;

import jakarta.annotation.PreDestroy;

/**
 * Executa a fila de downloads com limite de concorrência configurável.
 * As páginas são salvas em um diretório temporário (permitindo retomar após pausa ou reinício),
 * depois empacotadas em CBZ, verificadas e movidas atomicamente para a biblioteca.
 */
@Component
public class DownloadEngine {

    private static final Logger log = LoggerFactory.getLogger(DownloadEngine.class);
    private static final int PAGE_ATTEMPTS = 3;

    private final DownloadRepository downloads;
    private final ChapterRepository chapters;
    private final UpdateHistoryRepository history;
    private final ProviderRegistry registry;
    private final ProviderService providerService;
    private final StorageLayout layout;
    private final SettingsService settings;
    private final TransactionTemplate tx;
    private final Path tempRoot;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<UUID, Signal> running = new ConcurrentHashMap<>();

    static final class Signal {
        volatile boolean pause;
        volatile boolean cancel;
    }

    public DownloadEngine(DownloadRepository downloads, ChapterRepository chapters, UpdateHistoryRepository history,
                          ProviderRegistry registry, ProviderService providerService, StorageLayout layout,
                          SettingsService settings, AppProperties properties, PlatformTransactionManager txManager) {
        this.downloads = downloads;
        this.chapters = chapters;
        this.history = history;
        this.registry = registry;
        this.providerService = providerService;
        this.layout = layout;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
        this.tempRoot = Path.of(properties.storage().dataDir(), "tmp", "downloads").toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        Integer n = tx.execute(s -> downloads.requeueInterrupted());
        if (n != null && n > 0) {
            log.info("{} download(s) interrompido(s) foram recolocados na fila", n);
        }
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void dispatch() {
        int slots = settings.maxConcurrentDownloads() - running.size();
        if (slots <= 0) {
            return;
        }
        List<Download> queued = downloads.findQueued(PageRequest.of(0, slots + running.size()));
        for (Download d : queued) {
            if (slots <= 0) {
                break;
            }
            if (running.containsKey(d.getId())) {
                continue;
            }
            if (claim(d.getId())) {
                Signal signal = new Signal();
                running.put(d.getId(), signal);
                slots--;
                executor.submit(() -> {
                    try {
                        run(d.getId(), signal);
                    } finally {
                        running.remove(d.getId());
                    }
                });
            }
        }
    }

    public boolean isRunning(UUID downloadId) {
        return running.containsKey(downloadId);
    }

    public void requestPause(UUID downloadId) {
        Signal s = running.get(downloadId);
        if (s != null) {
            s.pause = true;
        }
    }

    public void requestCancel(UUID downloadId) {
        Signal s = running.get(downloadId);
        if (s != null) {
            s.cancel = true;
        } else {
            deleteTemp(downloadId);
        }
    }

    public int runningCount() {
        return running.size();
    }

    private boolean claim(UUID id) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Download d = downloads.findById(id).orElse(null);
            if (d == null || d.getStatus() != DownloadStatus.QUEUED) {
                return false;
            }
            d.setStatus(DownloadStatus.DOWNLOADING);
            d.setStartedAt(Instant.now());
            d.setAttempts(d.getAttempts() + 1);
            d.setErrorMessage(null);
            return true;
        }));
    }

    record Job(UUID downloadId, UUID chapterId, UUID contentId, String contentTitle, ContentType type, BigDecimal number,
               String title, String volume, String externalId, String providerCode, String language, String scanlator,
               String existingLocalPath, boolean downloadable, String web, boolean auto) {
    }

    void run(UUID downloadId, Signal signal) {
        Job job;
        try {
            job = loadJob(downloadId);
        } catch (RuntimeException e) {
            fail(downloadId, null, "Não foi possível carregar o download: " + e.getMessage());
            return;
        }
        try {
            if (job.existingLocalPath() != null && Files.exists(Path.of(job.existingLocalPath()))) {
                complete(job, Path.of(job.existingLocalPath()), false);
                return;
            }
            if (!job.downloadable() || job.providerCode() == null) {
                fail(downloadId, job, "Esta fonte não permite download deste capítulo.");
                return;
            }
            StorageLayout.Target target = layout.targetFor(job.chapterId(), job.type(), job.contentTitle(), job.number(),
                    job.title(), job.externalId(), job.language(), job.scanlator());
            if (target.existingReusable()) {
                try {
                    if (CbzWriter.verify(target.path()) > 0) {
                        log.info("Arquivo existente reaproveitado: {}", target.path());
                        complete(job, target.path(), false);
                        return;
                    }
                } catch (IOException e) {
                    log.warn("Arquivo existente inválido será substituído: {} ({})", target.path(), e.getMessage());
                }
            }
            ContentProvider provider = registry.require(job.providerCode());
            List<PageRef> pages = providerService.pages(job.providerCode(), job.externalId());
            Path temp = tempRoot.resolve(downloadId.toString());
            Files.createDirectories(temp);
            updateProgress(downloadId, 0, pages.size(), 0, 0);

            long sessionBytes = 0;
            long totalBytes = 0;
            long started = System.nanoTime();
            for (int i = 0; i < pages.size(); i++) {
                if (signal.cancel) {
                    deleteTemp(downloadId);
                    return;
                }
                if (signal.pause) {
                    return;
                }
                Optional<Path> existing = existingPage(temp, i);
                if (existing.isPresent()) {
                    totalBytes += Files.size(existing.get());
                } else {
                    FetchedImage image = fetchWithRetry(provider, pages.get(i), job);
                    String mime = ImageTypes.detect(image.data());
                    if (mime == null) {
                        throw new IOException("A página " + (i + 1) + " não é uma imagem válida.");
                    }
                    Path part = temp.resolve(String.format("%04d.%s.part", i, ImageTypes.extensionFor(mime)));
                    Files.write(part, image.data());
                    Files.move(part, temp.resolve(String.format("%04d.%s", i, ImageTypes.extensionFor(mime))), StandardCopyOption.REPLACE_EXISTING);
                    sessionBytes += image.data().length;
                    totalBytes += image.data().length;
                }
                double seconds = Math.max(0.001, Duration.ofNanos(System.nanoTime() - started).toMillis() / 1000.0);
                if (!updateProgress(downloadId, i + 1, pages.size(), totalBytes, (long) (sessionBytes / seconds))) {
                    return; // pausado ou cancelado pela API
                }
            }

            List<Path> files = listPages(temp);
            if (files.size() != pages.size()) {
                throw new IOException("Quantidade de páginas baixadas (" + files.size() + ") difere do esperado (" + pages.size() + ").");
            }
            Path part = target.path().resolveSibling(target.path().getFileName() + ".part");
            CbzWriter.write(part, files, new CbzWriter.ComicInfo(job.contentTitle(), job.number(), job.title(), job.volume(),
                    job.language(), job.scanlator(), job.web(), files.size()));
            int verified = CbzWriter.verify(part);
            if (verified != files.size()) {
                Files.deleteIfExists(part);
                throw new IOException("Falha na verificação de integridade do CBZ.");
            }
            try {
                Files.move(part, target.path(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target.path(), StandardCopyOption.REPLACE_EXISTING);
            }
            complete(job, target.path(), true);
            deleteTemp(downloadId);
        } catch (Exception e) {
            log.warn("Download {} falhou: {}", downloadId, e.getMessage());
            fail(downloadId, job, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private FetchedImage fetchWithRetry(ContentProvider provider, PageRef page, Job job) throws IOException {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= PAGE_ATTEMPTS; attempt++) {
            try {
                return provider.fetchPage(page);
            } catch (RuntimeException e) {
                last = e;
                if (attempt < PAGE_ATTEMPTS) {
                    try {
                        Thread.sleep(Duration.ofSeconds(1L << (attempt - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Download interrompido", ie);
                    }
                    if (attempt == 2) {
                        // URLs de páginas podem expirar (ex.: MangaDex@Home); renova a lista
                        providerService.evictChapters();
                    }
                }
            }
        }
        throw new IOException("Falha ao baixar página " + (page.index() + 1) + " após " + PAGE_ATTEMPTS + " tentativas: " + last.getMessage(), last);
    }

    private Job loadJob(UUID downloadId) {
        return tx.execute(s -> {
            Download d = downloads.findById(downloadId).orElseThrow();
            Chapter c = chapters.findWithContent(d.getChapter().getId()).orElseThrow();
            String code = c.getSource() == null ? null : c.getSource().getProvider().getCode();
            return new Job(downloadId, c.getId(), c.getContent().getId(), c.getContent().getTitle(), c.getContent().getType(),
                    c.getNumber(), c.getTitle(), c.getVolume(), c.getExternalId(), code, c.getLanguage(), c.getScanlator(),
                    c.getLocalPath(), c.isDownloadable(), c.getSource() == null ? null : c.getSource().getUrl(),
                    d.isAutoTriggered());
        });
    }

    /** Atualiza progresso; retorna falso se o download não está mais em andamento (pausado/cancelado). */
    private boolean updateProgress(UUID id, int done, int total, long bytes, long speed) {
        return Boolean.TRUE.equals(tx.execute(s -> {
            Download d = downloads.findById(id).orElse(null);
            if (d == null || d.getStatus() != DownloadStatus.DOWNLOADING) {
                return false;
            }
            d.setTotalPages(total);
            d.setDownloadedPages(done);
            d.setBytesDownloaded(bytes);
            d.setSpeedBps(speed);
            d.setProgress(total == 0 ? 0 : Math.min(99, done * 100 / total));
            return true;
        }));
    }

    private void complete(Job job, Path file, boolean downloaded) throws IOException {
        long size = Files.size(file);
        String checksum = CbzWriter.sha256(file);
        tx.executeWithoutResult(s -> {
            Chapter c = chapters.findById(job.chapterId()).orElseThrow();
            c.setLocalPath(file.toString());
            c.setFileSize(size);
            c.setChecksum(checksum);
            c.setDownloadedAt(Instant.now());
            Download d = downloads.findById(job.downloadId()).orElseThrow();
            d.setStatus(DownloadStatus.COMPLETED);
            d.setProgress(100);
            d.setSpeedBps(0);
            d.setFilePath(file.toString());
            d.setFinishedAt(Instant.now());
            if (d.getTotalPages() > 0) {
                d.setDownloadedPages(d.getTotalPages());
            }
            if (job.auto()) {
                history.save(new UpdateHistory(c.getContent(), c, null, UpdateHistory.EventType.DOWNLOAD_COMPLETED,
                        "Download automático concluído: " + c.displayLabel(job.type().isComic()) + " de " + job.contentTitle()));
            }
        });
        log.info("Download concluído{}: {}", downloaded ? "" : " (arquivo existente)", file);
    }

    private void fail(UUID downloadId, Job job, String message) {
        tx.executeWithoutResult(s -> downloads.findById(downloadId).ifPresent(d -> {
            if (d.getStatus() == DownloadStatus.CANCELLED || d.getStatus() == DownloadStatus.PAUSED) {
                return;
            }
            d.setStatus(DownloadStatus.FAILED);
            d.setErrorMessage(message);
            d.setSpeedBps(0);
            d.setFinishedAt(Instant.now());
            Chapter c = d.getChapter();
            history.save(new UpdateHistory(c.getContent(), c, null, UpdateHistory.EventType.DOWNLOAD_FAILED,
                    "Falha no download de " + (job == null ? "capítulo" : c.displayLabel(job.type().isComic()) + " de " + job.contentTitle()) + ": " + message));
        }));
    }

    private static Optional<Path> existingPage(Path temp, int index) throws IOException {
        String prefix = String.format("%04d.", index);
        try (Stream<Path> s = Files.list(temp)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix) && !p.getFileName().toString().endsWith(".part")).findFirst();
        }
    }

    private static List<Path> listPages(Path temp) throws IOException {
        try (Stream<Path> s = Files.list(temp)) {
            return s.filter(p -> !p.getFileName().toString().endsWith(".part"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    void deleteTemp(UUID downloadId) {
        Path temp = tempRoot.resolve(downloadId.toString());
        if (!Files.exists(temp)) {
            return;
        }
        try (Stream<Path> s = Files.walk(temp)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            log.debug("Não foi possível remover temporários de {}: {}", downloadId, e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        running.values().forEach(s -> s.pause = true);
        executor.shutdownNow();
    }

    List<UUID> runningIds() {
        return new ArrayList<>(running.keySet());
    }
}
