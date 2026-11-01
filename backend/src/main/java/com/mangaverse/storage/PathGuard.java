package com.mangaverse.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.mangaverse.common.ApiException;
import com.mangaverse.config.AppProperties;

/**
 * Restringe todo acesso a arquivos aos diretórios permitidos (MANGAVERSE_STORAGE_ALLOWED_ROOTS).
 * Links simbólicos são resolvidos antes da verificação para impedir fuga do diretório.
 */
@Component
public class PathGuard {

    private static final Logger log = LoggerFactory.getLogger(PathGuard.class);

    private final List<Path> roots;

    @Autowired
    public PathGuard(AppProperties properties) {
        this(properties.storage().allowedRoots());
    }

    public PathGuard(List<String> allowedRoots) {
        List<Path> resolved = new ArrayList<>();
        for (String r : allowedRoots) {
            if (r == null || r.isBlank()) {
                continue;
            }
            Path p = Path.of(r.trim()).toAbsolutePath().normalize();
            try {
                Files.createDirectories(p);
                resolved.add(p.toRealPath());
            } catch (IOException e) {
                log.warn("Diretório permitido inacessível, ignorado: {} ({})", p, e.getMessage());
            }
        }
        if (resolved.isEmpty()) {
            throw new IllegalStateException("Nenhum diretório de armazenamento permitido está acessível. Configure MANGAVERSE_STORAGE_ALLOWED_ROOTS.");
        }
        this.roots = List.copyOf(resolved);
    }

    public List<Path> roots() {
        return roots;
    }

    /** Valida e retorna o caminho canônico, lançando 403 se estiver fora dos diretórios permitidos. */
    public Path requireAllowed(String path) {
        if (path == null || path.isBlank() || path.indexOf('\0') >= 0) {
            throw ApiException.badRequest("Caminho inválido.");
        }
        return requireAllowed(Path.of(path.trim()));
    }

    public Path requireAllowed(Path path) {
        Path canonical = canonical(path);
        if (canonical == null || roots.stream().noneMatch(canonical::startsWith)) {
            throw ApiException.forbidden("Caminho fora dos diretórios permitidos: " + path);
        }
        return canonical;
    }

    public boolean isAllowed(Path path) {
        Path canonical = canonical(path);
        return canonical != null && roots.stream().anyMatch(canonical::startsWith);
    }

    /**
     * Resolve o caminho real; para caminhos ainda inexistentes, resolve o ancestral existente mais próximo
     * e reanexa o restante normalizado.
     */
    static Path canonical(Path path) {
        try {
            Path abs = path.toAbsolutePath().normalize();
            if (Files.exists(abs, LinkOption.NOFOLLOW_LINKS)) {
                return abs.toRealPath();
            }
            Path existing = abs;
            Path remainder = Path.of("");
            while (existing != null && !Files.exists(existing)) {
                remainder = existing.getFileName().resolve(remainder);
                existing = existing.getParent();
            }
            if (existing == null) {
                return null;
            }
            return existing.toRealPath().resolve(remainder).normalize();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Resolve um nome de entrada de arquivo compactado dentro de {@code baseDir}, bloqueando Zip Slip. */
    public static Path safeResolve(Path baseDir, String entryName) {
        if (entryName == null || entryName.isBlank() || entryName.indexOf('\0') >= 0) {
            throw new SecurityException("Nome de entrada inválido");
        }
        String normalizedName = entryName.replace('\\', '/');
        if (normalizedName.startsWith("/") || normalizedName.matches("^[A-Za-z]:.*")) {
            throw new SecurityException("Entrada com caminho absoluto: " + entryName);
        }
        Path base = baseDir.toAbsolutePath().normalize();
        Path target = base.resolve(normalizedName).normalize();
        if (!target.startsWith(base) || target.equals(base)) {
            throw new SecurityException("Entrada tenta sair do diretório de destino: " + entryName);
        }
        return target;
    }
}
