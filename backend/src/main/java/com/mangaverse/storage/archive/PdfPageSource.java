package com.mangaverse.storage.archive;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/** Renderiza páginas de PDF sob demanda como JPEG. */
public class PdfPageSource implements PageSource {

    private static final float DPI = 144f;

    private final PDDocument document;
    private final PDFRenderer renderer;

    public PdfPageSource(Path file) throws IOException {
        this.document = Loader.loadPDF(file.toFile());
        this.renderer = new PDFRenderer(document);
    }

    @Override
    public List<String> pageNames() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < document.getNumberOfPages(); i++) {
            names.add("page-" + (i + 1));
        }
        return names;
    }

    @Override
    public synchronized PageData read(int index) throws IOException {
        if (index < 0 || index >= document.getNumberOfPages()) {
            throw new IndexOutOfBoundsException("Página inexistente: " + index);
        }
        BufferedImage image = renderer.renderImageWithDPI(index, DPI, ImageType.RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", out);
        return new PageData(out.toByteArray(), "image/jpeg");
    }

    @Override
    public void close() throws IOException {
        document.close();
    }
}
