package com.mangaverse.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.mangaverse.common.ApiException;
import com.mangaverse.content.Chapter;
import com.mangaverse.content.ChapterRepository;
import com.mangaverse.content.Covers;
import com.mangaverse.settings.SettingsService;
import com.mangaverse.storage.PathGuard;

@Service
public class DownloadService {

    private final DownloadRepository downloads;
    private final ChapterRepository chapters;
    private final DownloadEngine engine;
    private final SettingsService settings;
    private final PathGuard pathGuard;
    private final TransactionTemplate tx;

    public DownloadService(DownloadRepository downloads, ChapterRepository chapters, DownloadEngine engine,
                           SettingsService settings, PathGuard pathGuard, PlatformTransactionManager txManager) {
        this.downloads = downloads;
        this.chapters = chapters;
        this.engine = engine;
        this.settings = settings;
        this.pathGuard = pathGuard;
        this.tx = new TransactionTemplate(txManager);
    }

    public record DownloadView(String id, String chapterId, String contentId, String contentTitle, String coverUrl,
                               String chapterLabel, String status, int progress, int totalPages, int downloadedPages,
                               long bytesDownloaded, long speedBps, String errorMessage, int attempts, String filePath,
                               boolean autoTriggered, Instant createdAt, Instant startedAt, Instant finishedAt) {
    }

    public record Skipped(String chapterId, String reason) {
    }

    public record EnqueueResult(int queued, List<Skipped> skipped) {
    }

    public record Summary(long queued, long downloading, long paused, long failed, int running, int maxConcurrent) {
    }

    /** Enfileira capítulos, ignorando os já baixados, os que já estão na fila e os não autorizados. */
    public EnqueueResult enqueue(Collection<UUID> chapterIds, boolean auto) {
        int queued = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (UUID id : chapterIds) {
            String reason;
            try {
                reason = tx.execute(s -> enqueueOne(id, auto));
            } catch (DataIntegrityViolationException e) {
                reason = "Já está na fila";
            }
            if (reason == null) {
                queued++;
            } else {
                skipped.add(new Skipped(id.toString(), reason));
            }
        }
        return new EnqueueResult(queued, skipped);
    }

    private String enqueueOne(UUID chapterId, boolean auto) {
        Chapter c = chapters.findById(chapterId).orElse(null);
        if (c == null) {
            return "Capítulo não encontrado";
        }
        if (c.isLocal() && c.getLocalPath() != null && Files.exists(Path.of(c.getLocalPath()))) {
            return "Já baixado";
        }
        if (!c.isDownloadable() || c.getSource() == null) {
            return c.getExternalUrl() != null ? "Disponível apenas no site oficial" : "A fonte não permite download";
        }
        if (downloads.existsByChapterIdAndStatusIn(chapterId, DownloadStatus.ACTIVE)) {
            return "Já está na fila";
        }
        if (c.isLocal()) {
            c.setLocalPath(null); // arquivo sumiu do disco: permite baixar novamente
        }
        downloads.save(new Download(c, auto));
        return null;
    }

    /** Baixa um volume (ou todos os faltantes), escolhendo uma versão por número de capítulo. */
    public EnqueueResult enqueueContent(UUID contentId, String volume, boolean onlyMissing) {
        List<Chapter> all = chapters.findByContentOrdered(contentId);
        String preferred = settings.preferredLanguage();
        Map<String, Chapter> chosen = new LinkedHashMap<>();
        for (Chapter c : all) {
            if (volume != null && !Objects.equals(volume, c.getVolume())) {
                continue;
            }
            if (!c.isDownloadable() || (onlyMissing && c.isLocal())) {
                continue;
            }
            String key = c.getNumber() == null ? "id:" + c.getId() : c.getNumber().stripTrailingZeros().toPlainString();
            Chapter current = chosen.get(key);
            if (current == null || (!Objects.equals(current.getLanguage(), preferred) && Objects.equals(c.getLanguage(), preferred))) {
                chosen.put(key, c);
            }
        }
        if (chosen.isEmpty()) {
            throw ApiException.badRequest("Nenhum capítulo disponível para download com esses critérios.");
        }
        return enqueue(chosen.values().stream().map(Chapter::getId).toList(), false);
    }

    @Transactional(readOnly = true)
    public List<DownloadView> list(int limit) {
        return downloads.findForListing(PageRequest.of(0, Math.max(1, Math.min(limit, 500)))).stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public Summary summary() {
        return new Summary(downloads.countByStatus(DownloadStatus.QUEUED), downloads.countByStatus(DownloadStatus.DOWNLOADING),
                downloads.countByStatus(DownloadStatus.PAUSED), downloads.countByStatus(DownloadStatus.FAILED),
                engine.runningCount(), settings.maxConcurrentDownloads());
    }

    @Transactional
    public void pause(UUID id) {
        Download d = get(id);
        if (d.getStatus() != DownloadStatus.QUEUED && d.getStatus() != DownloadStatus.DOWNLOADING) {
            throw ApiException.conflict("Só é possível pausar downloads na fila ou em andamento.");
        }
        d.setStatus(DownloadStatus.PAUSED);
        d.setSpeedBps(0);
        engine.requestPause(id);
    }

    @Transactional
    public void resume(UUID id) {
        Download d = get(id);
        if (d.getStatus() != DownloadStatus.PAUSED) {
            throw ApiException.conflict("O download não está pausado.");
        }
        if (engine.isRunning(id)) {
            throw ApiException.conflict("Aguarde a pausa ser concluída.");
        }
        d.setStatus(DownloadStatus.QUEUED);
    }

    @Transactional
    public void cancel(UUID id) {
        Download d = get(id);
        if (!DownloadStatus.ACTIVE.contains(d.getStatus())) {
            throw ApiException.conflict("O download não está ativo.");
        }
        d.setStatus(DownloadStatus.CANCELLED);
        d.setSpeedBps(0);
        d.setFinishedAt(Instant.now());
        engine.requestCancel(id);
    }

    @Transactional
    public void retry(UUID id) {
        Download d = get(id);
        if (d.getStatus() != DownloadStatus.FAILED && d.getStatus() != DownloadStatus.CANCELLED) {
            throw ApiException.conflict("Só é possível tentar novamente downloads com falha ou cancelados.");
        }
        if (downloads.existsByChapterIdAndStatusIn(d.getChapter().getId(), DownloadStatus.ACTIVE)) {
            throw ApiException.conflict("Este capítulo já está na fila.");
        }
        d.setStatus(DownloadStatus.QUEUED);
        d.setErrorMessage(null);
        d.setFinishedAt(null);
    }

    @Transactional
    public int clearFinished() {
        return downloads.deleteFinished();
    }

    public record IntegrityIssue(String chapterId, String path, String problem) {
    }

    public record IntegrityReport(int checked, int ok, List<IntegrityIssue> issues) {
    }

    /** Verifica a integridade dos arquivos baixados (existência, ZIP válido e checksum). */
    public IntegrityReport verifyAll() {
        List<Chapter> local = tx.execute(s -> chapters.findAllLocal());
        int ok = 0;
        List<IntegrityIssue> issues = new ArrayList<>();
        for (Chapter c : local) {
            Path p = Path.of(c.getLocalPath());
            String problem = null;
            if (!pathGuard.isAllowed(p)) {
                problem = "Fora dos diretórios permitidos";
            } else if (!Files.exists(p)) {
                problem = "Arquivo não encontrado";
            } else if (c.getChecksum() != null) {
                try {
                    CbzWriter.verify(p);
                    if (!c.getChecksum().equals(CbzWriter.sha256(p))) {
                        problem = "Checksum diferente do registrado (arquivo alterado)";
                    }
                } catch (IOException e) {
                    problem = e.getMessage();
                }
            }
            if (problem == null) {
                ok++;
            } else {
                issues.add(new IntegrityIssue(c.getId().toString(), p.toString(), problem));
            }
        }
        return new IntegrityReport(local.size(), ok, issues);
    }

    private Download get(UUID id) {
        return downloads.findById(id).orElseThrow(() -> ApiException.notFound("Download não encontrado."));
    }

    private DownloadView toView(Download d) {
        Chapter c = d.getChapter();
        var content = c.getContent();
        return new DownloadView(d.getId().toString(), c.getId().toString(), content.getId().toString(), content.getTitle(),
                Covers.resolve(content.getCoverUrl(), content.getId()), c.displayLabel(content.getType().isComic()),
                d.getStatus().name(), d.getProgress(), d.getTotalPages(), d.getDownloadedPages(), d.getBytesDownloaded(),
                d.getSpeedBps(), d.getErrorMessage(), d.getAttempts(), d.getFilePath(), d.isAutoTriggered(),
                d.getCreatedAt(), d.getStartedAt(), d.getFinishedAt());
    }

}
