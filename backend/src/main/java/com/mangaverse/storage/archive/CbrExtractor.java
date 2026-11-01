package com.mangaverse.storage.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mangaverse.config.AppProperties;

/**
 * Extrai arquivos CBR (RAR) usando uma ferramenta externa: bsdtar (libarchive), unrar ou 7z.
 * A extração acontece em um diretório de cache interno e cada arquivo extraído é verificado
 * contra Zip Slip e links simbólicos antes de ser servido.
 */
@Component
public class CbrExtractor {

    private static final Logger log = LoggerFactory.getLogger(CbrExtractor.class);
    private static final List<String> TOOLS = List.of("bsdtar", "unrar", "7z");

    private final Path cacheRoot;
    private final String configured;
    private volatile String detected;

    public CbrExtractor(AppProperties properties) {
        this.cacheRoot = Path.of(properties.storage().dataDir(), "cache", "cbr").toAbsolutePath().normalize();
        this.configured = properties.storage().cbrExtractor();
    }

    public String tool() {
        if (detected != null) {
            return detected;
        }
        if (configured != null && !configured.isBlank() && !"auto".equalsIgnoreCase(configured)) {
            detected = TOOLS.contains(configured) ? configured : null;
            return detected;
        }
        for (String t : TOOLS) {
            if (onPath(t)) {
                detected = t;
                return t;
            }
        }
        return null;
    }

    public boolean available() {
        return tool() != null;
    }

    /** Extrai (uma única vez, com cache) e retorna o diretório com as imagens. */
    public synchronized Path extract(Path cbr) throws IOException {
        String tool = tool();
        if (tool == null) {
            throw new IOException("Leitura de CBR requer 'bsdtar' (libarchive), 'unrar' ou '7z' instalado no sistema.");
        }
        String key = cbr.toRealPath() + "|" + Files.getLastModifiedTime(cbr).toMillis() + "|" + Files.size(cbr);
        Path target = cacheRoot.resolve(sha1(key));
        if (Files.isDirectory(target) && Files.exists(target.resolve(".complete"))) {
            return target;
        }
        deleteRecursively(target);
        Files.createDirectories(target);
        List<String> command = switch (tool) {
            case "bsdtar" -> List.of("bsdtar", "-x", "--no-same-owner", "--no-same-permissions", "-f", cbr.toString(), "-C", target.toString());
            case "unrar" -> List.of("unrar", "x", "-o+", "-y", "-idq", cbr.toString(), target + "/");
            default -> List.of("7z", "x", "-y", "-bd", "-o" + target, cbr.toString());
        };
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output;
        try {
            output = process.getInputStream().readNBytes(64 * 1024);
            if (!process.waitFor(180, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Tempo limite ao extrair CBR.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Extração interrompida.", e);
        }
        if (process.exitValue() != 0) {
            deleteRecursively(target);
            throw new IOException("Falha ao extrair CBR com " + tool + ": " + new String(output, StandardCharsets.UTF_8).trim());
        }
        verifyExtracted(target);
        Files.createFile(target.resolve(".complete"));
        return target;
    }

    /** Remove links simbólicos e garante que nada foi escrito fora do diretório de destino. */
    static void verifyExtracted(Path dir) throws IOException {
        Path real = dir.toRealPath();
        List<Path> all = new ArrayList<>();
        try (Stream<Path> s = Files.walk(real)) {
            s.forEach(all::add);
        }
        for (Path p : all) {
            if (Files.isSymbolicLink(p)) {
                Files.delete(p);
                continue;
            }
            Path normalized = p.toAbsolutePath().normalize();
            if (!normalized.startsWith(real) || (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !p.toRealPath().startsWith(real))) {
                deleteRecursively(real);
                throw new SecurityException("Arquivo CBR contém entradas fora do diretório de extração.");
            }
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static boolean onPath(String tool) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path p = Path.of(dir, tool);
            Path exe = Path.of(dir, tool + ".exe");
            if (Files.isExecutable(p) || Files.isExecutable(exe)) {
                return true;
            }
        }
        log.debug("Ferramenta {} não encontrada no PATH", tool);
        return false;
    }

    private static String sha1(String s) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
