package com.mangaverse.storage.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mangaverse.download.CbzWriter;

class ArchiveReadersTest {

    @TempDir
    Path tmp;

    static byte[] png(int w, int h) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    static byte[] jpg() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "jpg", out);
        return out.toByteArray();
    }

    private Path zip(String name, String... entries) throws IOException {
        Path file = tmp.resolve(name);
        try (OutputStream o = Files.newOutputStream(file); ZipOutputStream z = new ZipOutputStream(o)) {
            for (String e : entries) {
                z.putNextEntry(new ZipEntry(e));
                z.write(e.endsWith(".png") ? png(4, 4) : e.endsWith(".jpg") ? jpg() : "texto".getBytes());
                z.closeEntry();
            }
        }
        return file;
    }

    @Test
    void cbzListsPagesInNaturalOrderAndIgnoresUnsafeEntries() throws Exception {
        Path cbz = zip("cap.cbz", "10.jpg", "2.jpg", "1.png", "../evil.jpg", "/abs.jpg", "__MACOSX/1.jpg",
                ".hidden.jpg", "ComicInfo.xml", "notas.txt", "sub/11.jpg");
        try (CbzPageSource source = new CbzPageSource(cbz)) {
            assertThat(source.pageNames()).containsExactly("1.png", "2.jpg", "10.jpg", "sub/11.jpg");
            PageData first = source.read(0);
            assertThat(first.contentType()).isEqualTo("image/png");
            assertThat(source.read(1).contentType()).isEqualTo("image/jpeg");
            assertThatThrownBy(() -> source.read(99)).isInstanceOf(IndexOutOfBoundsException.class);
        }
    }

    @Test
    void folderSourceReadsImagesOnly() throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("capitulo"));
        Files.write(dir.resolve("p10.png"), png(2, 2));
        Files.write(dir.resolve("p9.png"), png(2, 2));
        Files.writeString(dir.resolve("leia.txt"), "x");
        try (FolderPageSource source = new FolderPageSource(dir, false)) {
            assertThat(source.pageNames()).containsExactly("p9.png", "p10.png");
            assertThat(source.read(1).contentType()).isEqualTo("image/png");
        }
    }

    @Test
    void pdfPagesAreRenderedOnDemand() throws Exception {
        Path pdf = tmp.resolve("hq.pdf");
        try (PDDocument doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.addPage(new PDPage());
            doc.save(pdf.toFile());
        }
        try (PdfPageSource source = new PdfPageSource(pdf)) {
            assertThat(source.pageNames()).hasSize(2);
            PageData page = source.read(1);
            assertThat(page.contentType()).isEqualTo("image/jpeg");
            assertThat(ImageTypes.detect(page.data())).isEqualTo("image/jpeg");
        }
    }

    @Test
    void cbzWriterProducesVerifiableArchiveWithComicInfo() throws Exception {
        Path p1 = Files.write(tmp.resolve("0000.png"), png(3, 3));
        Path p2 = Files.write(tmp.resolve("0001.jpg"), jpg());
        Path cbz = tmp.resolve("Capitulo 001.cbz");
        CbzWriter.write(cbz, List.of(p1, p2), new CbzWriter.ComicInfo("Série <Teste>", new java.math.BigDecimal("1"),
                "Título & cia", "1", "pt-br", "Grupo", null, 2));
        assertThat(CbzWriter.verify(cbz)).isEqualTo(2);
        try (CbzPageSource source = new CbzPageSource(cbz)) {
            assertThat(source.pageNames()).containsExactly("001.png", "002.jpg");
        }
        try (var zip = new java.util.zip.ZipFile(cbz.toFile())) {
            String xml = new String(zip.getInputStream(zip.getEntry("ComicInfo.xml")).readAllBytes());
            assertThat(xml).contains("<Series>Série &lt;Teste&gt;</Series>").contains("<Title>Título &amp; cia</Title>");
        }
        assertThat(CbzWriter.sha256(cbz)).hasSize(64);
    }

    @Test
    void verifyDetectsCorruptedArchivesAndFakeImages() throws Exception {
        Path fake = zip("fake.cbz", "001.jpg");
        // sobrescreve com uma "imagem" que não é imagem
        try (OutputStream o = Files.newOutputStream(fake); ZipOutputStream z = new ZipOutputStream(o)) {
            z.putNextEntry(new ZipEntry("001.jpg"));
            z.write("<html>não sou imagem</html>".getBytes());
            z.closeEntry();
        }
        assertThatThrownBy(() -> CbzWriter.verify(fake)).isInstanceOf(IOException.class);
        Path truncated = tmp.resolve("trunc.cbz");
        byte[] good = Files.readAllBytes(zip("ok.cbz", "001.png", "002.png"));
        Files.write(truncated, java.util.Arrays.copyOf(good, good.length / 2));
        assertThatThrownBy(() -> CbzWriter.verify(truncated)).isInstanceOf(IOException.class);
    }

    @Test
    void cbrVerificationRemovesSymlinks() throws Exception {
        Path extracted = Files.createDirectories(tmp.resolve("cbr-out"));
        Files.write(extracted.resolve("001.png"), png(2, 2));
        Path link = Files.createSymbolicLink(extracted.resolve("evil.png"), Path.of("/etc/passwd"));
        CbrExtractor.verifyExtracted(extracted);
        assertThat(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.exists(extracted.resolve("001.png"))).isTrue();
    }

    @Test
    void imageTypeDetectionUsesMagicBytes() throws Exception {
        assertThat(ImageTypes.detect(png(1, 1))).isEqualTo("image/png");
        assertThat(ImageTypes.detect(jpg())).isEqualTo("image/jpeg");
        assertThat(ImageTypes.detect("RIFF0000WEBPVP8 ".getBytes())).isEqualTo("image/webp");
        assertThat(ImageTypes.detect("<svg onload=alert(1)>".getBytes())).isNull();
    }
}
