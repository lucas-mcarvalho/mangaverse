package com.mangaverse.translation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.mangaverse.common.ApiException;
import com.mangaverse.config.AppProperties;
import com.mangaverse.reader.ReaderService;
import com.mangaverse.storage.archive.PageData;
import com.mangaverse.user.AppUser;

import jakarta.annotation.PreDestroy;

/** Durable, resumable edition preparation; original archives are never modified. */
@Service
public class TranslationService {
    private static final Logger log = LoggerFactory.getLogger(TranslationService.class);
    /** Bumped when the worker's output changes, so editions prepared by an older engine are redone. */
    private static final String EDITION = "en-ptbr-v2";
    private final ReaderService reader;
    private final TranslationClient client;
    private final Path root;
    private final ThreadPoolExecutor editions = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(100), Thread.ofPlatform().name("translation-edition-", 0).factory());
    private final ExecutorService pages = Executors.newFixedThreadPool(2, Thread.ofPlatform().name("translation-page-", 0).factory());
    private final Set<UUID> running = ConcurrentHashMap.newKeySet();
    private final java.util.Map<UUID, java.util.concurrent.FutureTask<Void>> queuedTasks = new ConcurrentHashMap<>();
    private volatile boolean stopping;

    public TranslationService(ReaderService reader, TranslationClient client, AppProperties properties) {
        this.reader = reader;
        this.client = client;
        this.root = Path.of(properties.storage().dataDir()).resolve("translations");
    }

    public record View(String chapterId, String status, int totalPages, int completedPages, String error,
                       String updatedAt, String sourceLanguage, String targetLanguage) {}

    private Path directory(UUID id) { return root.resolve(id.toString()).resolve(EDITION); }
    private Path image(UUID id, int index) { return directory(id).resolve(String.format("%05d.png", index)); }

    public synchronized View status(UUID id) {
        Path manifest = directory(id).resolve("job.properties");
        if (!Files.exists(manifest)) return new View(id.toString(), "NONE", 0, 0, null, null, "en", "pt-br");
        try (var input = Files.newInputStream(manifest)) {
            Properties p = new Properties();
            p.load(input);
            return new View(id.toString(), p.getProperty("status"), Integer.parseInt(p.getProperty("total")),
                    Integer.parseInt(p.getProperty("completed")), p.getProperty("error"), p.getProperty("updated"), "en", "pt-br");
        } catch (IOException | IllegalArgumentException e) {
            throw ApiException.conflict("Não foi possível ler o estado da tradução.");
        }
    }

    public View start(UUID id, AppUser user) {
        // Validate existence and readability before accepting a job; no DB transaction spans processing.
        int total = reader.open(id, user).pageCount();
        if (total > 2000) throw ApiException.badRequest("Prepare edições com até 2000 páginas por vez.");
        synchronized (this) {
            View previous = status(id);
            if (running.contains(id)) {
                if ("CANCELLED".equals(previous.status())) {
                    throw ApiException.conflict("A página em andamento ainda está encerrando. Aguarde antes de retomar.");
                }
                return previous;
            }
            if ("READY".equals(previous.status()) && previous.totalPages() == total && countReady(id, total) == total) return previous;
            if (previous.totalPages() != 0 && previous.totalPages() != total) {
                // A changed source must not reuse pages from an edition with a different length.
                try (var files = Files.list(directory(id))) {
                    for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".png")).toList()) Files.delete(file);
                } catch (IOException e) {
                    throw ApiException.conflict("Não foi possível atualizar as páginas da tradução.");
                }
            }
            View queued = write(id, "QUEUED", total, countReady(id, total), null);
            submit(id, total);
            return queued;
        }
    }

    /** Discards the saved edition (for example, one prepared before translator fixes) and prepares it again. */
    public View redo(UUID id, AppUser user) {
        synchronized (this) {
            if (running.contains(id)) throw ApiException.conflict("Cancele a preparação em andamento antes de refazer.");
            try (var files = Files.list(directory(id))) {
                for (Path file : files.toList()) Files.delete(file);
            } catch (java.nio.file.NoSuchFileException e) {
                // Nothing prepared yet.
            } catch (IOException e) {
                throw ApiException.conflict("Não foi possível apagar a tradução anterior.");
            }
        }
        return start(id, user);
    }

    public synchronized View cancel(UUID id) {
        View current = status(id);
        if (!Set.of("QUEUED", "PROCESSING").contains(current.status())) return current;
        View cancelled = write(id, "CANCELLED", current.totalPages(), current.completedPages(), null);
        var task = queuedTasks.get(id);
        if (task != null && editions.remove(task)) {
            task.cancel(false);
            queuedTasks.remove(id);
            running.remove(id);
        }
        return cancelled;
    }

    /** Páginas traduzidas, em ordem, de uma edição pronta. */
    public synchronized List<Path> readyPages(UUID id) {
        View current = status(id);
        if (!"READY".equals(current.status())) throw ApiException.conflict("Aguarde a tradução da edição terminar.");
        List<Path> result = new ArrayList<>(current.totalPages());
        for (int i = 0; i < current.totalPages(); i++) {
            if (!Files.isRegularFile(image(id, i))) throw ApiException.conflict("Página traduzida ausente; use Traduzir novamente.");
            result.add(image(id, i));
        }
        return result;
    }

    public synchronized PageData page(UUID id, int index) {
        View current = status(id);
        if (!"READY".equals(current.status())) throw ApiException.conflict("Aguarde a tradução da edição terminar.");
        if (index < 0 || index >= current.totalPages()) throw ApiException.notFound("Página inexistente.");
        try {
            return new PageData(Files.readAllBytes(image(id, index)), "image/png");
        } catch (IOException e) {
            throw ApiException.notFound("Página traduzida não encontrada.");
        }
    }

    private void submit(UUID id, int total) {
        running.add(id);
        var task = new java.util.concurrent.FutureTask<Void>(() -> { prepare(id, total); return null; });
        queuedTasks.put(id, task);
        try {
            editions.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            running.remove(id);
            queuedTasks.remove(id);
            write(id, "FAILED", total, countReady(id, total), "Fila cheia. Tente novamente quando uma edição terminar.");
            throw ApiException.conflict("Fila de traduções cheia.");
        }
    }

    private boolean active(UUID id) {
        return !stopping && !Thread.currentThread().isInterrupted() && !"CANCELLED".equals(status(id).status());
    }

    private void prepare(UUID id, int total) {
        List<Future<?>> batch = new ArrayList<>();
        try {
            synchronized (this) {
                if (!active(id)) return;
                write(id, "PROCESSING", total, countReady(id, total), null);
            }
            // Bounded batches keep memory usage to two source/result images and respect source load.
            for (int start = 0; start < total && active(id); start += 2) {
                batch.clear();
                for (int i = start; i < Math.min(total, start + 2); i++) {
                    final int index = i;
                    if (Files.exists(image(id, index))) continue;
                    batch.add(pages.submit(() -> {
                        if (!active(id)) return;
                        byte[] translated = client.translate(reader.page(id, index));
                        synchronized (TranslationService.this) {
                            if (!active(id)) return;
                            try {
                                atomicWrite(image(id, index), translated);
                                write(id, "PROCESSING", total, countReady(id, total), null);
                            } catch (IOException e) {
                                throw ApiException.conflict("Sem acesso ao disco para salvar a página traduzida.");
                            }
                        }
                    }));
                }
                // Drain both tasks before retry becomes possible, including when one fails.
                Exception failure = null;
                for (Future<?> future : batch) {
                    try { future.get(); } catch (Exception e) { failure = e; }
                }
                if (failure != null) throw failure;
            }
            synchronized (this) {
                if (active(id)) write(id, "READY", total, total, null);
            }
        } catch (Exception e) {
            synchronized (this) {
                if (!stopping && !"CANCELLED".equals(status(id).status())) {
                    Throwable cause = e;
                    while (cause.getCause() != null) cause = cause.getCause();
                    String message = cause instanceof ApiException ? cause.getMessage() : "Falha ao preparar tradução. Tente novamente para retomar.";
                    write(id, "FAILED", total, countReady(id, total), message);
                    log.warn("Falha na tradução da edição {}: {}", id, e.toString());
                }
            }
        } finally {
            running.remove(id);
            queuedTasks.remove(id);
        }
    }

    private int countReady(UUID id, int total) {
        int count = 0;
        for (int i = 0; i < total; i++) if (Files.isRegularFile(image(id, i))) count++;
        return count;
    }

    private synchronized View write(UUID id, String state, int total, int completed, String error) {
        Properties p = new Properties();
        String updated = Instant.now().toString();
        p.setProperty("status", state);
        p.setProperty("total", Integer.toString(total));
        p.setProperty("completed", Integer.toString(completed));
        p.setProperty("updated", updated);
        if (error != null) p.setProperty("error", error);
        try {
            var bytes = new java.io.ByteArrayOutputStream();
            p.store(bytes, "MangaVerse English to Portuguese translation " + EDITION);
            atomicWrite(directory(id).resolve("job.properties"), bytes.toByteArray());
        } catch (IOException e) {
            throw ApiException.conflict("Não foi possível salvar o progresso da tradução no disco.");
        }
        return new View(id.toString(), state, total, completed, error, updated, "en", "pt-br");
    }

    private static void atomicWrite(Path path, byte[] data) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".part");
        Files.write(temp, data);
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void resume() {
        if (!Files.isDirectory(root)) return;
        try (var dirs = Files.list(root)) {
            for (Path dir : dirs.toList()) {
                UUID id;
                try { id = UUID.fromString(dir.getFileName().toString()); } catch (IllegalArgumentException e) { continue; }
                View view = status(id);
                if (Set.of("QUEUED", "PROCESSING").contains(view.status())) submit(id, view.totalPages());
            }
        } catch (IOException | ApiException e) {
            log.warn("Não foi possível retomar todas as traduções: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        stopping = true;
        editions.shutdownNow();
        pages.shutdownNow();
    }
}
