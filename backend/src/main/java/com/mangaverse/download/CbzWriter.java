package com.mangaverse.download;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import com.mangaverse.storage.FileNaming;
import com.mangaverse.storage.archive.ImageTypes;

/** Monta e valida arquivos CBZ. */
public final class CbzWriter {

    private CbzWriter() {
    }

    public record ComicInfo(String series, BigDecimal number, String title, String volume, String language,
                            String scanlator, String web, int pageCount) {
    }

    /** Grava as imagens (já ordenadas) no CBZ, com ComicInfo.xml opcional. */
    public static void write(Path target, List<Path> pages, ComicInfo info) throws IOException {
        try (OutputStream out = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            zip.setLevel(Deflater.BEST_SPEED);
            int width = Math.max(3, String.valueOf(pages.size()).length());
            for (int i = 0; i < pages.size(); i++) {
                Path page = pages.get(i);
                String ext = FileNaming.extension(page.getFileName().toString());
                zip.putNextEntry(new ZipEntry(String.format("%0" + width + "d.%s", i + 1, ext)));
                Files.copy(page, zip);
                zip.closeEntry();
            }
            if (info != null) {
                zip.putNextEntry(new ZipEntry("ComicInfo.xml"));
                zip.write(comicInfoXml(info).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
    }

    /**
     * Verifica a integridade: o ZIP abre, todas as entradas podem ser lidas (CRC conferido pelo JDK)
     * e as imagens têm assinatura válida. Retorna a quantidade de páginas.
     */
    public static int verify(Path cbz) throws IOException {
        int images = 0;
        try (ZipFile zip = new ZipFile(cbz.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] head = in.readNBytes(16);
                    in.transferTo(OutputStream.nullOutputStream()); // força a verificação de CRC
                    if (ImageTypes.hasImageExtension(e.getName())) {
                        if (ImageTypes.detect(head) == null) {
                            throw new IOException("Página corrompida ou inválida: " + e.getName());
                        }
                        images++;
                    }
                }
            }
        } catch (java.util.zip.ZipException e) {
            throw new IOException("Arquivo CBZ corrompido: " + e.getMessage(), e);
        }
        return images;
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(((DigestInputStream) in).getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String comicInfoXml(ComicInfo i) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<ComicInfo xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");
        tag(sb, "Series", i.series());
        tag(sb, "Number", i.number() == null ? null : i.number().stripTrailingZeros().toPlainString());
        tag(sb, "Title", i.title());
        tag(sb, "Volume", i.volume());
        tag(sb, "LanguageISO", i.language());
        tag(sb, "ScanInformation", i.scanlator());
        tag(sb, "Web", i.web());
        tag(sb, "PageCount", String.valueOf(i.pageCount()));
        return sb.append("</ComicInfo>\n").toString();
    }

    private static void tag(StringBuilder sb, String name, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String escaped = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
        sb.append("  <").append(name).append('>').append(escaped).append("</").append(name).append(">\n");
    }
}
