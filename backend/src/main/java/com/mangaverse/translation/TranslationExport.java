package com.mangaverse.translation;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.mangaverse.common.ApiException;
import com.mangaverse.download.CbzWriter;
import com.mangaverse.reader.ReaderService;
import com.mangaverse.storage.FileNaming;
import com.mangaverse.storage.PathGuard;

/** Exporta uma edição traduzida como CBZ: numa pasta permitida do servidor ou direto para o navegador. */
@Service
public class TranslationExport {
    private final TranslationService translations;
    private final ReaderService reader;
    private final PathGuard guard;

    public TranslationExport(TranslationService translations, ReaderService reader, PathGuard guard) {
        this.translations = translations;
        this.reader = reader;
        this.guard = guard;
    }

    public record SaveRequest(String directory) {}

    public record Saved(String path) {}

    public String fileName(UUID id) {
        return FileNaming.sanitize(reader.displayName(id) + " (PT-BR)") + ".cbz";
    }

    public Saved save(UUID id, String directory) {
        Path folder = guard.requireAllowed(directory);
        if (!Files.isDirectory(folder)) throw ApiException.badRequest("Pasta não encontrada.");
        List<Path> pages = translations.readyPages(id);
        String name = fileName(id);
        Path target = available(folder, name);
        Path temp = folder.resolve("." + name + ".part");
        try {
            CbzWriter.write(temp, pages, info(id, pages.size()));
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            try { Files.deleteIfExists(temp); } catch (IOException ignored) { /* best effort */ }
            throw ApiException.conflict("Não foi possível salvar o CBZ nessa pasta. Verifique permissões e espaço em disco.");
        }
        return new Saved(target.toString());
    }

    public List<Path> pages(UUID id) { return translations.readyPages(id); }

    public void write(UUID id, List<Path> pages, OutputStream out) throws IOException {
        CbzWriter.write(out, pages, info(id, pages.size()));
    }

    private CbzWriter.ComicInfo info(UUID id, int pageCount) {
        return new CbzWriter.ComicInfo(reader.displayName(id), null, null, null, "pt", "MangaVerse (tradução automática)", null, pageCount);
    }

    /** Nunca sobrescreve: "Nome (PT-BR).cbz", "Nome (PT-BR) (2).cbz"... */
    private static Path available(Path folder, String name) {
        Path target = folder.resolve(name);
        String stem = name.substring(0, name.length() - ".cbz".length());
        for (int n = 2; Files.exists(target); n++) target = folder.resolve(stem + " (" + n + ").cbz");
        return target;
    }
}
