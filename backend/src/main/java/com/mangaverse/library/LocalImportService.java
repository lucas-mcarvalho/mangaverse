package com.mangaverse.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mangaverse.common.ApiException;
import com.mangaverse.content.Chapter;
import com.mangaverse.content.ChapterRepository;
import com.mangaverse.content.Content;
import com.mangaverse.content.ContentRepository;
import com.mangaverse.content.Covers;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.settings.SettingsService;
import com.mangaverse.storage.FileNaming;
import com.mangaverse.storage.PathGuard;
import com.mangaverse.storage.archive.ArchiveService;
import com.mangaverse.storage.archive.NaturalOrder;
import com.mangaverse.storage.archive.PageSource;
import com.mangaverse.user.AppUser;

/**
 * Importa arquivos locais. Aceita tanto uma pasta de biblioteca (subpastas = séries) quanto uma pasta de série
 * (arquivos CBZ/CBR/PDF ou subpastas de imagens = capítulos). Os arquivos nunca são movidos ou renomeados.
 */
@Service
public class LocalImportService {

    private static final Logger log = LoggerFactory.getLogger(LocalImportService.class);

    private final PathGuard pathGuard;
    private final ContentRepository contents;
    private final ChapterRepository chapters;
    private final LibraryService libraryService;
    private final ArchiveService archives;
    private final SettingsService settings;
    private final TransactionTemplate tx;

    public LocalImportService(PathGuard pathGuard, ContentRepository contents, ChapterRepository chapters,
                              LibraryService libraryService, ArchiveService archives, SettingsService settings,
                              PlatformTransactionManager txManager) {
        this.pathGuard = pathGuard;
        this.contents = contents;
        this.chapters = chapters;
        this.libraryService = libraryService;
        this.archives = archives;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
    }

    public record ImportResult(int seriesFound, int seriesCreated, int chaptersAdded, int chaptersSkipped,
                               List<String> errors, List<String> contentIds) {
    }

    public ImportResult importDirectory(AppUser user, String rawPath, ContentType type) {
        Path root = pathGuard.requireAllowed(rawPath);
        if (!Files.isDirectory(root)) {
            throw ApiException.badRequest("O caminho informado não é um diretório: " + root);
        }
        ContentType effectiveType = type != null ? type
                : root.startsWith(settings.comicLibraryPath().toAbsolutePath()) ? ContentType.COMIC : ContentType.MANGA;
        List<Path> seriesDirs = looksLikeSeries(root) ? List.of(root) : listDirs(root);
        int created = 0;
        int added = 0;
        int skipped = 0;
        List<String> errors = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        for (Path series : seriesDirs) {
            try {
                SeriesResult r = importSeries(user, series, effectiveType);
                created += r.created ? 1 : 0;
                added += r.added;
                skipped += r.skipped;
                ids.add(r.contentId);
            } catch (RuntimeException | IOException e) {
                log.warn("Falha ao importar {}: {}", series, e.getMessage());
                errors.add(series.getFileName() + ": " + e.getMessage());
            }
        }
        return new ImportResult(seriesDirs.size(), created, added, skipped, errors, ids);
    }

    private record SeriesResult(String contentId, boolean created, int added, int skipped) {
    }

    private SeriesResult importSeries(AppUser user, Path seriesDir, ContentType type) throws IOException {
        Path real = pathGuard.requireAllowed(seriesDir);
        List<Path> chapterPaths = listChapters(real);
        String localPath = real.toString();
        int[] counters = new int[2];
        boolean[] created = new boolean[1];
        Content content = tx.execute(status -> {
            Content c = contents.findByLocalPath(localPath).orElseGet(() -> {
                Content n = new Content(real.getFileName().toString(), type);
                n.setLocalPath(localPath);
                n.setCoverUrl(Covers.LOCAL);
                created[0] = true;
                return contents.save(n);
            });
            for (Path p : chapterPaths) {
                String chapterPath = p.toString();
                if (chapters.findByLocalPath(chapterPath).isPresent()) {
                    counters[1]++;
                    continue;
                }
                String name = p.getFileName().toString();
                Chapter ch = new Chapter(c, null, null);
                ch.setLocalPath(chapterPath);
                ch.setNumber(FileNaming.parseNumber(name));
                ch.setNumberLabel(ch.getNumber() == null ? null : ch.getNumber().stripTrailingZeros().toPlainString());
                ch.setVolume(FileNaming.parseVolume(name));
                ch.setTitle(Files.isDirectory(p) ? name : FileNaming.stripExtension(name));
                ch.setReadable(true);
                ch.setDownloadable(false);
                ch.setFileSize(sizeOf(p));
                ch.setPageCount(quickPageCount(p));
                chapters.save(ch);
                counters[0]++;
            }
            return c;
        });
        libraryService.addContent(user, content.getId(), null);
        return new SeriesResult(content.getId().toString(), created[0], counters[0], counters[1]);
    }

    /** A pasta é uma série se contém arquivos de capítulo ou subpastas de imagens. */
    static boolean looksLikeSeries(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return s.anyMatch(p -> ArchiveService.isChapterFile(p) || ArchiveService.isImageFolder(p));
        } catch (IOException e) {
            return false;
        }
    }

    static List<Path> listChapters(Path seriesDir) throws IOException {
        try (Stream<Path> s = Files.list(seriesDir)) {
            return s.filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> ArchiveService.isChapterFile(p) || ArchiveService.isImageFolder(p))
                    .map(Path::toAbsolutePath)
                    .sorted((a, b) -> NaturalOrder.INSTANCE.compare(a.getFileName().toString(), b.getFileName().toString()))
                    .toList();
        }
    }

    private static List<Path> listDirs(Path root) {
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted((a, b) -> NaturalOrder.INSTANCE.compare(a.getFileName().toString(), b.getFileName().toString()))
                    .toList();
        } catch (IOException e) {
            throw ApiException.badRequest("Não foi possível listar o diretório: " + e.getMessage());
        }
    }

    private static Long sizeOf(Path p) {
        try {
            if (Files.isDirectory(p)) {
                try (Stream<Path> s = Files.list(p)) {
                    return s.filter(Files::isRegularFile).mapToLong(f -> f.toFile().length()).sum();
                }
            }
            return Files.size(p);
        } catch (IOException e) {
            return null;
        }
    }

    private Integer quickPageCount(Path p) {
        String ext = Files.isDirectory(p) ? "dir" : FileNaming.extension(p.getFileName().toString());
        if (!ext.equals("cbz") && !ext.equals("zip") && !ext.equals("dir")) {
            return null; // PDF/CBR são contados na primeira leitura
        }
        try (PageSource source = archives.open(p)) {
            return source.pageNames().size();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
