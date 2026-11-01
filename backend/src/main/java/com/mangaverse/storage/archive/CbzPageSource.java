package com.mangaverse.storage.archive;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Lê páginas diretamente do CBZ (ZIP) sem extrair o arquivo para o disco. */
public class CbzPageSource implements PageSource {

    static final long MAX_ENTRY_SIZE = 64L * 1024 * 1024;

    private final ZipFile zip;
    private final List<ZipEntry> entries;

    public CbzPageSource(Path file) throws IOException {
        this.zip = new ZipFile(file.toFile());
        List<ZipEntry> list = new ArrayList<>();
        Enumeration<? extends ZipEntry> e = zip.entries();
        while (e.hasMoreElements()) {
            ZipEntry entry = e.nextElement();
            if (!entry.isDirectory() && isSafePageName(entry.getName())) {
                list.add(entry);
            }
        }
        list.sort((a, b) -> NaturalOrder.INSTANCE.compare(a.getName(), b.getName()));
        this.entries = List.copyOf(list);
    }

    /** Ignora entradas suspeitas (absolutas, com "..", ocultas ou que não são imagens). */
    static boolean isSafePageName(String name) {
        String n = name.replace('\\', '/');
        if (n.startsWith("/") || n.contains("../") || n.equals("..") || n.matches("^[A-Za-z]:.*")) {
            return false;
        }
        if (n.startsWith("__MACOSX/") || n.contains("/.") || n.startsWith(".")) {
            return false;
        }
        return ImageTypes.hasImageExtension(n);
    }

    @Override
    public List<String> pageNames() {
        return entries.stream().map(ZipEntry::getName).toList();
    }

    @Override
    public PageData read(int index) throws IOException {
        if (index < 0 || index >= entries.size()) {
            throw new IndexOutOfBoundsException("Página inexistente: " + index);
        }
        ZipEntry entry = entries.get(index);
        if (entry.getSize() > MAX_ENTRY_SIZE) {
            throw new IOException("Página excede o tamanho máximo permitido");
        }
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] data = in.readNBytes((int) MAX_ENTRY_SIZE + 1);
            if (data.length > MAX_ENTRY_SIZE) {
                throw new IOException("Página excede o tamanho máximo permitido");
            }
            String type = ImageTypes.detect(data);
            return new PageData(data, type != null ? type : "application/octet-stream");
        }
    }

    @Override
    public void close() throws IOException {
        zip.close();
    }
}
