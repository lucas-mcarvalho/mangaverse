package com.mangaverse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mangaverse.download.CbzWriter;
import com.mangaverse.library.LocalImportService;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.reader.ReaderService;

class LocalImportIntegrationTest extends IntegrationTestBase {

    @Autowired LocalImportService importer;
    @Autowired ReaderService reader;

    @Test
    void importsLibraryFolderIdempotentlyAndServesPagesFromCbzAndFolders() throws Exception {
        Path root = Files.createDirectories(STORAGE.resolve("Importar-" + System.nanoTime()));
        Path series = Files.createDirectories(root.resolve("Invincible"));
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(5, 5, BufferedImage.TYPE_INT_RGB), "png", png);
        Path page = Files.write(STORAGE.resolve("page.png"), png.toByteArray());
        CbzWriter.write(series.resolve("Invincible #002.cbz"), List.of(page, page), null);
        CbzWriter.write(series.resolve("Invincible #001.cbz"), List.of(page), null);
        Path folder = Files.createDirectories(series.resolve("Edicao 3"));
        Files.copy(page, folder.resolve("01.png"));

        var result = importer.importDirectory(admin(), root.toString(), ContentType.COMIC);
        assertThat(result.seriesFound()).isEqualTo(1);
        assertThat(result.chaptersAdded()).isEqualTo(3);
        assertThat(importer.importDirectory(admin(), root.toString(), ContentType.COMIC).chaptersAdded()).isZero();

        var content = contents.findAll().get(0);
        assertThat(content.getType()).isEqualTo(ContentType.COMIC);
        var list = chapters.findByContentOrdered(content.getId());
        assertThat(list).extracting(c -> c.getNumber().intValue()).containsExactly(1, 2, 3);
        assertThat(list.get(1).getPageCount()).isEqualTo(2);

        var view = reader.open(list.get(1).getId(), admin());
        assertThat(view.pageCount()).isEqualTo(2);
        assertThat(view.local()).isTrue();
        assertThat(view.defaultMode()).isEqualTo("ltr");
        assertThat(reader.page(list.get(1).getId(), 1).contentType()).isEqualTo("image/png");
        assertThat(reader.page(list.get(2).getId(), 0).contentType()).isEqualTo("image/png");
    }
}
