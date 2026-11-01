package com.mangaverse.library;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.stereotype.Component;

import com.mangaverse.common.ApiException;
import com.mangaverse.storage.PathGuard;
import com.mangaverse.storage.archive.NaturalOrder;

/** Lista subdiretórios dentro dos diretórios permitidos, para escolha de pastas na interface. */
@Component
public class DirectoryBrowser {

    private final PathGuard pathGuard;

    public DirectoryBrowser(PathGuard pathGuard) {
        this.pathGuard = pathGuard;
    }

    public record Listing(String path, String parent, List<String> directories, List<String> roots, Long freeBytes) {
    }

    public Listing browse(String raw) {
        List<String> roots = pathGuard.roots().stream().map(Path::toString).toList();
        if (raw == null || raw.isBlank()) {
            return new Listing(null, null, roots, roots, null);
        }
        Path dir = pathGuard.requireAllowed(raw);
        if (!Files.isDirectory(dir)) {
            throw ApiException.badRequest("Não é um diretório: " + dir);
        }
        List<String> dirs;
        try (Stream<Path> s = Files.list(dir)) {
            dirs = s.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .map(Path::toString)
                    .sorted(NaturalOrder.INSTANCE)
                    .limit(500)
                    .toList();
        } catch (IOException e) {
            throw ApiException.badRequest("Não foi possível listar o diretório: " + e.getMessage());
        }
        Path parent = dir.getParent();
        String parentStr = parent != null && pathGuard.isAllowed(parent) ? parent.toString() : null;
        Long free = null;
        try {
            free = Files.getFileStore(dir).getUsableSpace();
        } catch (IOException ignored) {
            // espaço livre é informativo
        }
        return new Listing(dir.toString(), parentStr, dirs, roots, free);
    }
}
