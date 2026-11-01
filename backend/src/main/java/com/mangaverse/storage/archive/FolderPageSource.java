package com.mangaverse.storage.archive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** Capítulo representado por uma pasta de imagens (JPG/PNG/WebP). */
public class FolderPageSource implements PageSource {

    private final Path dir;
    private final List<Path> files;

    public FolderPageSource(Path dir, boolean recursive) throws IOException {
        this.dir = dir.toRealPath();
        try (Stream<Path> s = recursive ? Files.walk(this.dir, 4) : Files.list(this.dir)) {
            this.files = s.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> ImageTypes.hasImageExtension(p.getFileName().toString()))
                    .sorted((a, b) -> NaturalOrder.INSTANCE.compare(this.dir.relativize(a).toString(), this.dir.relativize(b).toString()))
                    .toList();
        }
    }

    @Override
    public List<String> pageNames() {
        return files.stream().map(p -> dir.relativize(p).toString()).toList();
    }

    @Override
    public PageData read(int index) throws IOException {
        if (index < 0 || index >= files.size()) {
            throw new IndexOutOfBoundsException("Página inexistente: " + index);
        }
        Path file = files.get(index);
        if (!file.toRealPath().startsWith(dir)) {
            throw new SecurityException("Arquivo fora do diretório do capítulo");
        }
        byte[] data = Files.readAllBytes(file);
        String type = ImageTypes.detect(data);
        return new PageData(data, type != null ? type : "application/octet-stream");
    }

    @Override
    public void close() {
        // nada a liberar
    }
}
