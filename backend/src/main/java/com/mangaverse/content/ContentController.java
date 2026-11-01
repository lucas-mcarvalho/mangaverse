package com.mangaverse.content;

import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mangaverse.common.ApiException;
import com.mangaverse.library.LocalImportService;
import com.mangaverse.reader.ReaderService;
import com.mangaverse.storage.archive.PageData;
import com.mangaverse.user.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

@RestController
@RequestMapping("/api/contents")
public class ContentController {

    private final ContentQueryService query;
    private final ContentService contentService;
    private final ContentRepository contents;
    private final ContentSourceRepository sources;
    private final ChapterRepository chapters;
    private final ReaderService reader;
    private final LocalImportService importer;
    private final CurrentUser currentUser;
    private final SourceFinderService finder;
    private final Cache<UUID, PageData> covers = Caffeine.newBuilder()
            .maximumWeight(64L * 1024 * 1024).weigher((UUID k, PageData v) -> v.data().length)
            .expireAfterWrite(Duration.ofHours(6)).build();

    public ContentController(ContentQueryService query, ContentService contentService, ContentRepository contents,
                             ContentSourceRepository sources, ChapterRepository chapters, ReaderService reader,
                             LocalImportService importer, CurrentUser currentUser, SourceFinderService finder) {
        this.query = query;
        this.contentService = contentService;
        this.contents = contents;
        this.sources = sources;
        this.chapters = chapters;
        this.reader = reader;
        this.importer = importer;
        this.currentUser = currentUser;
        this.finder = finder;
    }

    @GetMapping("/{id}")
    public ContentViews.ContentView get(@PathVariable UUID id) {
        return query.view(id, currentUser.get());
    }

    /** Capa de títulos locais: primeira página do primeiro capítulo, reduzida. */
    @GetMapping("/{id}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable UUID id) {
        PageData data = covers.get(id, this::buildCover);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(data.contentType()))
                .cacheControl(CacheControl.maxAge(6, TimeUnit.HOURS).cachePrivate()).body(data.data());
    }

    private PageData buildCover(UUID id) {
        Chapter first = chapters.findByContentOrdered(id).stream().filter(Chapter::isLocal).findFirst()
                .orElseThrow(() -> ApiException.notFound("Sem capa disponível."));
        PageData page = reader.page(first.getId(), 0);
        return downscale(page, 480);
    }

    static PageData downscale(PageData page, int maxWidth) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(page.data()));
            if (src == null || src.getWidth() <= maxWidth) {
                return page; // formato não suportado pelo ImageIO (ex.: WebP) ou já pequeno
            }
            int h = (int) Math.round(src.getHeight() * (maxWidth / (double) src.getWidth()));
            BufferedImage out = new BufferedImage(maxWidth, h, BufferedImage.TYPE_INT_RGB);
            out.getGraphics().drawImage(src.getScaledInstance(maxWidth, h, Image.SCALE_SMOOTH), 0, 0, null);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bytes);
            return new PageData(bytes.toByteArray(), "image/jpeg");
        } catch (IOException e) {
            return page;
        }
    }

    public record LinkSourceRequest(@NotBlank String providerCode, @NotBlank String externalId) {
    }

    @PostMapping("/{id}/sources")
    public ContentViews.ContentView linkSource(@PathVariable UUID id, @Valid @RequestBody LinkSourceRequest request) {
        if (!contents.existsById(id)) {
            throw ApiException.notFound("Conteúdo não encontrado.");
        }
        contentService.importFromProvider(request.providerCode(), request.externalId(), id);
        covers.invalidate(id);
        return query.view(id, currentUser.get());
    }

    @DeleteMapping("/{id}/sources/{sourceId}")
    public ResponseEntity<Void> unlinkSource(@PathVariable UUID id, @PathVariable UUID sourceId) {
        ContentSource source = sources.findById(sourceId)
                .filter(s -> s.getContent().getId().equals(id))
                .orElseThrow(() -> ApiException.notFound("Fonte não encontrada."));
        boolean hasLocalChapters = chapters.findByContentOrdered(id).stream()
                .anyMatch(c -> c.isLocal() && c.getSource() != null && c.getSource().getId().equals(sourceId));
        if (hasLocalChapters) {
            throw ApiException.conflict("Há capítulos baixados vinculados a esta fonte. Remova-os antes de desvincular.");
        }
        sources.delete(source);
        return ResponseEntity.noContent().build();
    }

    public record RefreshResult(int newChapters, List<String> errors) {
    }

    /** Atualiza capítulos de todas as fontes (ignorando cache) e, para títulos locais, reescaneia a pasta. */
    @PostMapping("/{id}/refresh")
    public RefreshResult refresh(@PathVariable UUID id) {
        Content content = contents.findById(id).orElseThrow(() -> ApiException.notFound("Conteúdo não encontrado."));
        int created = 0;
        List<String> errors = new java.util.ArrayList<>();
        for (ContentSource s : sources.findByContentId(id)) {
            try {
                created += contentService.syncChapters(s.getId(), true).size();
            } catch (RuntimeException e) {
                errors.add(s.getProvider().getName() + ": " + e.getMessage());
            }
        }
        if (content.getLocalPath() != null) {
            var result = importer.importDirectory(currentUser.get(), content.getLocalPath(), content.getType());
            created += result.chaptersAdded();
            errors.addAll(result.errors());
        }
        return new RefreshResult(created, errors);
    }

    /** Procura o mesmo título em outras fontes habilitadas e vincula as encontradas. */
    @PostMapping("/{id}/find-sources")
    public SourceFinderService.FindResult findSources(@PathVariable UUID id) {
        SourceFinderService.FindResult result = finder.findMoreSources(id);
        covers.invalidate(id);
        return result;
    }

    public record MarkReadRequest(@NotEmpty List<UUID> chapterIds, boolean read) {
    }

    @PostMapping("/{id}/chapters/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, @Valid @RequestBody MarkReadRequest request) {
        reader.markRead(currentUser.get(), id, request.chapterIds(), request.read());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/continue")
    public ResponseEntity<ReaderService.ContinueView> continueReading(@PathVariable UUID id) {
        ReaderService.ContinueView view = reader.continueReading(currentUser.get(), id);
        return view == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(view);
    }

    @GetMapping("/{id}/summary")
    public Map<String, Object> summary(@PathVariable UUID id) {
        Content c = contents.findById(id).orElseThrow(() -> ApiException.notFound("Conteúdo não encontrado."));
        return Map.of("id", c.getId().toString(), "title", c.getTitle(), "type", c.getType().name());
    }
}
