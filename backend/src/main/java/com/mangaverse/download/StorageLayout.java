package com.mangaverse.download;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.mangaverse.content.ChapterRepository;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.settings.SettingsService;
import com.mangaverse.storage.FileNaming;
import com.mangaverse.storage.PathGuard;

/**
 * Define onde cada capítulo é salvo:
 * {@code <Biblioteca>/Mangas/<Série>/Capitulo 001.cbz} ou {@code <Biblioteca>/HQs/<Série>/Edicao 001.cbz}.
 */
@Component
public class StorageLayout {

    private final SettingsService settings;
    private final PathGuard pathGuard;
    private final ChapterRepository chapters;

    public StorageLayout(SettingsService settings, PathGuard pathGuard, ChapterRepository chapters) {
        this.settings = settings;
        this.pathGuard = pathGuard;
        this.chapters = chapters;
    }

    public record Target(Path path, boolean existingReusable) {
    }

    public Target targetFor(UUID chapterId, ContentType type, String seriesTitle, BigDecimal number, String title,
                            String externalId, String language, String scanlator) throws IOException {
        Path base = type.isComic() ? settings.comicLibraryPath() : settings.mangaLibraryPath();
        Path seriesDir = pathGuard.requireAllowed(base.resolve(FileNaming.sanitize(seriesTitle)));
        Files.createDirectories(seriesDir);
        String fileName = FileNaming.chapterFileName(type.isComic(), number, title, externalId);
        String stem = FileNaming.stripExtension(fileName);
        if (language != null && !Objects.equals(language, settings.preferredLanguage()) && !type.isComic()) {
            stem += " [" + FileNaming.sanitize(language) + "]";
        }
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidateStem = switch (attempt) {
                case 0 -> stem;
                case 1 -> scanlator == null ? null : stem + " [" + FileNaming.sanitize(scanlator) + "]";
                default -> stem + " (" + attempt + ")";
            };
            if (candidateStem == null) {
                continue;
            }
            Path candidate = pathGuard.requireAllowed(seriesDir.resolve(candidateStem + ".cbz"));
            var owner = chapters.findByLocalPath(candidate.toString());
            if (owner.isPresent()) {
                if (owner.get().getId().equals(chapterId)) {
                    return new Target(candidate, true);
                }
                continue; // pertence a outro capítulo (outra versão/scan)
            }
            // Arquivo sem dono no banco (ex.: biblioteca reaproveitada): pode ser adotado após verificação
            return new Target(candidate, Files.exists(candidate));
        }
        throw new IOException("Não foi possível definir um nome de arquivo livre para o capítulo.");
    }
}
