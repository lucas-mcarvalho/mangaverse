package com.mangaverse.storage.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.mangaverse.storage.FileNaming;

/** Abre a fonte de páginas adequada para cada formato suportado. */
@Service
public class ArchiveService {

    public static final Set<String> CHAPTER_EXTENSIONS = Set.of("cbz", "zip", "cbr", "rar", "pdf");

    private final CbrExtractor cbrExtractor;

    public ArchiveService(CbrExtractor cbrExtractor) {
        this.cbrExtractor = cbrExtractor;
    }

    public PageSource open(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return new FolderPageSource(path, false);
        }
        return switch (FileNaming.extension(path.getFileName().toString())) {
            case "cbz", "zip" -> new CbzPageSource(path);
            case "cbr", "rar" -> new FolderPageSource(cbrExtractor.extract(path), true);
            case "pdf" -> new PdfPageSource(path);
            default -> throw new IOException("Formato não suportado: " + path.getFileName());
        };
    }

    public static boolean isChapterFile(Path p) {
        return Files.isRegularFile(p) && CHAPTER_EXTENSIONS.contains(FileNaming.extension(p.getFileName().toString()));
    }

    public static boolean isImageFolder(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (var s = Files.list(dir)) {
            return s.anyMatch(f -> Files.isRegularFile(f) && ImageTypes.hasImageExtension(f.getFileName().toString()));
        } catch (IOException e) {
            return false;
        }
    }

    public boolean cbrSupported() {
        return cbrExtractor.available();
    }

    public String cbrTool() {
        return cbrExtractor.tool();
    }
}
