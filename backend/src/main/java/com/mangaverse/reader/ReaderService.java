package com.mangaverse.reader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mangaverse.common.ApiException;
import com.mangaverse.content.Chapter;
import com.mangaverse.content.ChapterRepository;
import com.mangaverse.content.Content;
import com.mangaverse.library.LibraryEntry;
import com.mangaverse.library.LibraryEntryRepository;
import com.mangaverse.library.LibraryStatus;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.FetchedImage;
import com.mangaverse.provider.spi.PageRef;
import com.mangaverse.storage.FileNaming;
import com.mangaverse.storage.PathGuard;
import com.mangaverse.storage.archive.ArchiveService;
import com.mangaverse.storage.archive.PageData;
import com.mangaverse.storage.archive.PageSource;
import com.mangaverse.storage.archive.PdfPageSource;
import com.mangaverse.user.AppUser;

/** Serve páginas para o leitor (arquivos locais ou provedores) e persiste o progresso de leitura. */
@Service
public class ReaderService {

    private final ChapterRepository chapters;
    private final ReadingProgressRepository progress;
    private final LibraryEntryRepository library;
    private final ProviderService providerService;
    private final ProviderRegistry registry;
    private final ArchiveService archives;
    private final PathGuard pathGuard;
    private final TransactionTemplate tx;
    private final Cache<String, PageData> remotePages = Caffeine.newBuilder()
            .maximumWeight(200L * 1024 * 1024)
            .weigher((String k, PageData v) -> v.data().length)
            .expireAfterAccess(Duration.ofMinutes(20))
            .build();
    private final Cache<Path, PageSource> openPdfs = Caffeine.newBuilder()
            .maximumSize(4)
            .expireAfterAccess(Duration.ofMinutes(3))
            .removalListener((Path k, PageSource v, com.github.benmanes.caffeine.cache.RemovalCause c) -> closeQuietly(v))
            .build();

    public ReaderService(ChapterRepository chapters, ReadingProgressRepository progress, LibraryEntryRepository library,
                         ProviderService providerService, ProviderRegistry registry, ArchiveService archives,
                         PathGuard pathGuard, PlatformTransactionManager txManager) {
        this.chapters = chapters;
        this.progress = progress;
        this.library = library;
        this.providerService = providerService;
        this.registry = registry;
        this.archives = archives;
        this.pathGuard = pathGuard;
        this.tx = new TransactionTemplate(txManager);
    }

    public record ReaderChapterView(String chapterId, String contentId, String contentTitle, ContentType contentType,
                                    String label, String title, String volume, int pageCount, List<String> pages,
                                    String prevChapterId, String nextChapterId, int startPage, boolean completed,
                                    boolean local, String providerCode, String defaultMode) {
    }

    public record ContinueView(String chapterId, int page, String label) {
    }

    public record ProgressRequest(int page, int totalPages, Boolean completed) {
    }

    public ReaderChapterView open(UUID chapterId, AppUser user) {
        ChapterInfo info = loadInfo(chapterId);
        int pageCount;
        if (info.localPath != null) {
            pageCount = withLocalSource(info.localPath, s -> s.pageNames().size());
        } else {
            if (!info.readable) {
                throw ApiException.conflict(info.externalUrl != null
                        ? "Este capítulo só está disponível no site oficial: " + info.externalUrl
                        : "Esta fonte não disponibiliza as páginas deste capítulo.");
            }
            pageCount = providerService.pages(info.providerCode, info.externalId).size();
        }
        if (pageCount == 0) {
            throw ApiException.conflict("Nenhuma página encontrada para este capítulo.");
        }
        List<String> pages = new ArrayList<>(pageCount);
        for (int i = 0; i < pageCount; i++) {
            pages.add("/api/reader/chapters/" + chapterId + "/pages/" + i);
        }
        ReadingProgress p = progress.findByUserIdAndChapterId(user.getId(), chapterId).orElse(null);
        int start = p == null || p.isCompleted() ? 0 : Math.min(p.getPage(), pageCount - 1);
        Neighbors n = neighbors(info.contentId, chapterId);
        return new ReaderChapterView(chapterId.toString(), info.contentId.toString(), info.contentTitle, info.type,
                info.label, info.title, info.volume, pageCount, pages, n.prev, n.next, start, p != null && p.isCompleted(),
                info.localPath != null, info.providerCode, defaultMode(info.type));
    }

    public PageData page(UUID chapterId, int index) {
        ChapterInfo info = loadInfo(chapterId);
        if (index < 0) {
            throw ApiException.badRequest("Página inválida.");
        }
        if (info.localPath != null) {
            return withLocalSource(info.localPath, s -> {
                if (index >= s.pageNames().size()) {
                    throw ApiException.notFound("Página inexistente.");
                }
                return s.read(index);
            });
        }
        if (!info.readable) {
            throw ApiException.conflict("Capítulo indisponível para leitura.");
        }
        String key = chapterId + "|" + index;
        PageData cached = remotePages.getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        List<PageRef> refs = providerService.pages(info.providerCode, info.externalId);
        if (index >= refs.size()) {
            throw ApiException.notFound("Página inexistente.");
        }
        FetchedImage image = registry.require(info.providerCode).fetchPage(refs.get(index));
        PageData data = new PageData(image.data(), image.contentType());
        remotePages.put(key, data);
        return data;
    }

    public void saveProgress(AppUser user, UUID chapterId, ProgressRequest req) {
        if (req.page() < 0 || req.totalPages() < 0) {
            throw ApiException.badRequest("Progresso inválido.");
        }
        try {
            doSaveProgress(user, chapterId, req);
        } catch (DataIntegrityViolationException e) {
            doSaveProgress(user, chapterId, req); // gravação concorrente do mesmo capítulo: tenta novamente como update
        }
    }

    private void doSaveProgress(AppUser user, UUID chapterId, ProgressRequest req) {
        tx.executeWithoutResult(status -> {
            Chapter chapter = chapters.findWithContent(chapterId).orElseThrow(() -> ApiException.notFound("Capítulo não encontrado."));
            ReadingProgress p = progress.findByUserIdAndChapterId(user.getId(), chapterId)
                    .orElseGet(() -> new ReadingProgress(user, chapter.getContent(), chapter));
            int total = Math.max(req.totalPages(), 0);
            int page = total > 0 ? Math.min(req.page(), total - 1) : req.page();
            p.setPage(page);
            p.setTotalPages(total);
            boolean done = req.completed() != null ? req.completed() : total > 0 && page >= total - 1;
            p.setCompleted(p.isCompleted() || done);
            progress.save(p);
            touchLibrary(user, chapter.getContent());
        });
    }

    @Transactional
    public void markRead(AppUser user, UUID contentId, Collection<UUID> chapterIds, boolean read) {
        for (UUID id : chapterIds) {
            Chapter chapter = chapters.findWithContent(id).orElseThrow(() -> ApiException.notFound("Capítulo não encontrado."));
            if (!chapter.getContent().getId().equals(contentId)) {
                throw ApiException.badRequest("Capítulo não pertence a este título.");
            }
            ReadingProgress p = progress.findByUserIdAndChapterId(user.getId(), id).orElse(null);
            if (read) {
                if (p == null) {
                    p = new ReadingProgress(user, chapter.getContent(), chapter);
                }
                p.setCompleted(true);
                progress.save(p);
            } else if (p != null) {
                progress.delete(p);
            }
        }
    }

    /** Próximo capítulo a ler: o último em andamento ou o seguinte ao último concluído. */
    @Transactional(readOnly = true)
    public ContinueView continueReading(AppUser user, UUID contentId) {
        List<Chapter> ordered = chapters.findByContentOrdered(contentId).stream()
                .filter(c -> c.isReadable() || c.isLocal()).toList();
        if (ordered.isEmpty()) {
            return null;
        }
        Map<UUID, ReadingProgress> byChapter = progress.findByUserIdAndContentId(user.getId(), contentId).stream()
                .collect(Collectors.toMap(p -> p.getChapter().getId(), Function.identity()));
        boolean comic = ordered.get(0).getContent().getType().isComic();
        ReadingProgress latest = byChapter.values().stream().max(java.util.Comparator.comparing(ReadingProgress::getUpdatedAt)).orElse(null);
        if (latest != null && !latest.isCompleted()) {
            Chapter c = latest.getChapter();
            return new ContinueView(c.getId().toString(), latest.getPage(), c.displayLabel(comic));
        }
        if (latest != null) {
            Neighbors n = neighbors(contentId, latest.getChapter().getId());
            if (n.next != null) {
                Chapter next = ordered.stream().filter(c -> c.getId().toString().equals(n.next)).findFirst().orElse(null);
                if (next != null) {
                    return new ContinueView(next.getId().toString(), 0, next.displayLabel(comic));
                }
            }
        }
        Chapter firstUnread = ordered.stream().filter(c -> !byChapter.containsKey(c.getId()) || !byChapter.get(c.getId()).isCompleted())
                .findFirst().orElse(ordered.get(0));
        return new ContinueView(firstUnread.getId().toString(), 0, firstUnread.displayLabel(comic));
    }

    private void touchLibrary(AppUser user, Content content) {
        LibraryEntry entry = library.findByUserIdAndContentId(user.getId(), content.getId())
                .orElseGet(() -> library.save(new LibraryEntry(user, content)));
        entry.setLastReadAt(Instant.now());
        if (entry.getStatus() == LibraryStatus.PLAN_TO_READ) {
            entry.setStatus(LibraryStatus.READING);
        }
    }

    record Neighbors(String prev, String next) {
    }

    /**
     * Anterior/próximo pela numeração. Quando há várias versões do mesmo número (scans/idiomas diferentes),
     * prefere a de mesmo idioma e mesmo grupo.
     */
    Neighbors neighbors(UUID contentId, UUID chapterId) {
        List<Chapter> ordered = chapters.findByContentOrdered(contentId).stream()
                .filter(c -> c.isReadable() || c.isLocal()).toList();
        int idx = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).getId().equals(chapterId)) {
                idx = i;
                break;
            }
        }
        if (idx < 0) {
            return new Neighbors(null, null);
        }
        Chapter current = ordered.get(idx);
        return new Neighbors(pick(ordered, idx, -1, current), pick(ordered, idx, 1, current));
    }

    static String pick(List<Chapter> ordered, int idx, int step, Chapter current) {
        List<Chapter> candidates = new ArrayList<>();
        java.math.BigDecimal target = null;
        for (int i = idx + step; i >= 0 && i < ordered.size(); i += step) {
            Chapter c = ordered.get(i);
            if (current.getNumber() != null && c.getNumber() != null && c.getNumber().compareTo(current.getNumber()) == 0) {
                continue; // outra versão do mesmo capítulo
            }
            if (target == null && candidates.isEmpty()) {
                target = c.getNumber();
                candidates.add(c);
            } else if (target != null && c.getNumber() != null && c.getNumber().compareTo(target) == 0) {
                candidates.add(c);
            } else {
                break;
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        // Mantém a fonte escolhida: idioma/grupo iguais não identificam o mesmo provedor.
        if (current.getSource() != null) {
            var sameSource = candidates.stream()
                    .filter(c -> c.getSource() != null && Objects.equals(c.getSource().getId(), current.getSource().getId()))
                    .findFirst();
            if (sameSource.isPresent()) {
                return sameSource.get().getId().toString();
            }
        }
        return candidates.stream()
                .filter(c -> Objects.equals(c.getLanguage(), current.getLanguage()) && Objects.equals(c.getScanlator(), current.getScanlator()))
                .findFirst()
                .or(() -> candidates.stream().filter(c -> Objects.equals(c.getLanguage(), current.getLanguage())).findFirst())
                .orElse(candidates.get(0)).getId().toString();
    }

    private record ChapterInfo(UUID contentId, String contentTitle, ContentType type, String label, String title,
                               String volume, String localPath, String providerCode, String externalId,
                               boolean readable, String externalUrl) {
    }

    private ChapterInfo loadInfo(UUID chapterId) {
        return tx.execute(status -> {
            Chapter c = chapters.findWithContent(chapterId).orElseThrow(() -> ApiException.notFound("Capítulo não encontrado."));
            Content content = c.getContent();
            String code = c.getSource() == null ? null : c.getSource().getProvider().getCode();
            return new ChapterInfo(content.getId(), content.getTitle(), content.getType(),
                    c.displayLabel(content.getType().isComic()), c.getTitle(), c.getVolume(), c.getLocalPath(), code,
                    c.getExternalId(), c.isReadable() && code != null, c.getExternalUrl());
        });
    }

    @FunctionalInterface
    interface SourceFn<T> {
        T apply(PageSource source) throws IOException;
    }

    <T> T withLocalSource(String localPath, SourceFn<T> fn) {
        Path path = pathGuard.requireAllowed(localPath);
        if (!Files.exists(path)) {
            throw ApiException.notFound("Arquivo do capítulo não encontrado no disco: " + path.getFileName());
        }
        try {
            if ("pdf".equals(FileNaming.extension(path.getFileName().toString()))) {
                PageSource pdf = openPdfs.get(path, p -> {
                    try {
                        return new PdfPageSource(p);
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                });
                return fn.apply(pdf);
            }
            try (PageSource source = archives.open(path)) {
                return fn.apply(source);
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            throw ApiException.conflict("Não foi possível ler o arquivo: " + e.getMessage());
        }
    }

    private static String defaultMode(ContentType type) {
        return switch (type) {
            case MANGA -> "rtl";
            case MANHWA, MANHUA -> "vertical";
            case COMIC -> "ltr";
        };
    }

    private static void closeQuietly(PageSource s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // nada a fazer
            }
        }
    }
}
