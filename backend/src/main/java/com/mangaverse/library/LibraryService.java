package com.mangaverse.library;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mangaverse.common.ApiException;
import com.mangaverse.common.TextNormalizer;
import com.mangaverse.content.ChapterRepository;
import com.mangaverse.content.Content;
import com.mangaverse.content.ContentRepository;
import com.mangaverse.content.ContentService;
import com.mangaverse.content.Covers;
import com.mangaverse.download.DownloadRepository;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.reader.ReadingProgress;
import com.mangaverse.reader.ReadingProgressRepository;
import com.mangaverse.user.AppUser;

@Service
public class LibraryService {

    private final LibraryEntryRepository entries;
    private final CategoryRepository categories;
    private final ContentRepository contents;
    private final ContentService contentService;
    private final ChapterRepository chapters;
    private final ReadingProgressRepository progress;
    private final DownloadRepository downloads;

    public LibraryService(LibraryEntryRepository entries, CategoryRepository categories, ContentRepository contents,
                          ContentService contentService, ChapterRepository chapters, ReadingProgressRepository progress,
                          DownloadRepository downloads) {
        this.entries = entries;
        this.categories = categories;
        this.contents = contents;
        this.contentService = contentService;
        this.chapters = chapters;
        this.progress = progress;
        this.downloads = downloads;
    }

    public record Filter(ContentType type, LibraryStatus status, Boolean favorite, UUID categoryId, String query,
                         Boolean monitored) {
    }

    public record AddRequest(String providerCode, String externalId, UUID contentId, LibraryStatus status,
                             Boolean monitored, Boolean autoDownload, Boolean favorite) {
    }

    public record UpdateRequest(LibraryStatus status, Boolean favorite, Boolean monitored, Boolean autoDownload,
                                UUID categoryId, Boolean clearCategory) {
    }

    @Transactional(readOnly = true)
    public List<LibraryViews.LibraryItem> list(AppUser user, Filter filter) {
        List<LibraryEntry> all = entries.findAllForUser(user.getId());
        String q = filter.query() == null ? "" : TextNormalizer.normalizeTitle(filter.query());
        List<LibraryEntry> filtered = all.stream()
                .filter(e -> filter.type() == null || e.getContent().getType() == filter.type())
                .filter(e -> filter.status() == null || e.getStatus() == filter.status())
                .filter(e -> filter.favorite() == null || e.isFavorite() == filter.favorite())
                .filter(e -> filter.monitored() == null || e.isMonitored() == filter.monitored())
                .filter(e -> filter.categoryId() == null || e.getCategory() != null && e.getCategory().getId().equals(filter.categoryId()))
                .filter(e -> q.isEmpty() || e.getContent().getNormalizedTitle().contains(q))
                .toList();
        return toItems(user, filtered);
    }

    List<LibraryViews.LibraryItem> toItems(AppUser user, List<LibraryEntry> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = list.stream().map(e -> e.getContent().getId()).toList();
        Map<UUID, Long> totals = toMap(chapters.countByContentIds(ids));
        Map<UUID, Long> downloaded = toMap(chapters.countDownloadedByContentIds(ids));
        Map<UUID, Long> read = toMap(progress.countCompletedByContent(user.getId(), ids));
        Map<UUID, Long> active = toMap(downloads.countActiveByContentIds(ids));
        Map<UUID, ReadingProgress> latest = new LinkedHashMap<>();
        for (ReadingProgress p : progress.findLatestForContents(user.getId(), ids)) {
            latest.putIfAbsent(p.getContent().getId(), p);
        }
        return list.stream().map(e -> {
            Content c = e.getContent();
            int total = totals.getOrDefault(c.getId(), 0L).intValue();
            int readCount = Math.min(total, read.getOrDefault(c.getId(), 0L).intValue());
            ReadingProgress last = latest.get(c.getId());
            return new LibraryViews.LibraryItem(e.getId().toString(), c.getId().toString(), c.getTitle(), c.getType(),
                    Covers.resolve(c.getCoverUrl(), c.getId()), e.getStatus().name(), e.isFavorite(), e.isMonitored(),
                    e.isAutoDownload(), e.getCategory() == null ? null : e.getCategory().getId().toString(),
                    e.getCategory() == null ? null : e.getCategory().getName(), e.getAddedAt(), e.getLastReadAt(),
                    total, readCount, Math.max(0, total - readCount), downloaded.getOrDefault(c.getId(), 0L).intValue(),
                    active.getOrDefault(c.getId(), 0L).intValue(),
                    last == null ? null : last.getChapter().displayLabel(c.getType().isComic()),
                    last == null ? null : last.getChapter().getId().toString(),
                    total == 0 ? 0 : (int) Math.round(readCount * 100.0 / total), c.getLocalPath() != null);
        }).toList();
    }

    /** Adiciona por provedor+ID externo ou por conteúdo já existente. Idempotente. */
    public LibraryEntry add(AppUser user, AddRequest req) {
        Content content;
        if (req.contentId() != null) {
            content = contents.findById(req.contentId()).orElseThrow(() -> ApiException.notFound("Conteúdo não encontrado."));
        } else if (req.providerCode() != null && req.externalId() != null) {
            content = contentService.importFromProvider(req.providerCode(), req.externalId(), null);
        } else {
            throw ApiException.badRequest("Informe providerCode e externalId, ou contentId.");
        }
        return addContent(user, content.getId(), req);
    }

    @Transactional
    public LibraryEntry addContent(AppUser user, UUID contentId, AddRequest req) {
        return entries.findByUserIdAndContentId(user.getId(), contentId).orElseGet(() -> {
            Content content = contents.getReferenceById(contentId);
            LibraryEntry entry = new LibraryEntry(user, content);
            entry.setStatus(req != null && req.status() != null ? req.status() : LibraryStatus.PLAN_TO_READ);
            entry.setMonitored(req != null && Boolean.TRUE.equals(req.monitored()));
            entry.setAutoDownload(req != null && Boolean.TRUE.equals(req.autoDownload()));
            entry.setFavorite(req != null && Boolean.TRUE.equals(req.favorite()));
            return entries.save(entry);
        });
    }

    @Transactional
    public LibraryEntry update(AppUser user, UUID entryId, UpdateRequest req) {
        LibraryEntry entry = entries.findForUser(entryId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Item da biblioteca não encontrado."));
        if (req.status() != null) {
            entry.setStatus(req.status());
        }
        if (req.favorite() != null) {
            entry.setFavorite(req.favorite());
        }
        if (req.monitored() != null) {
            entry.setMonitored(req.monitored());
        }
        if (req.autoDownload() != null) {
            entry.setAutoDownload(req.autoDownload());
        }
        if (Boolean.TRUE.equals(req.clearCategory())) {
            entry.setCategory(null);
        } else if (req.categoryId() != null) {
            entry.setCategory(categories.findByIdAndUserId(req.categoryId(), user.getId())
                    .orElseThrow(() -> ApiException.notFound("Categoria não encontrada.")));
        }
        return entry;
    }

    @Transactional
    public void remove(AppUser user, UUID entryId) {
        LibraryEntry entry = entries.findForUser(entryId, user.getId())
                .orElseThrow(() -> ApiException.notFound("Item da biblioteca não encontrado."));
        entries.delete(entry);
    }

    @Transactional(readOnly = true)
    public List<LibraryViews.CategoryView> categories(AppUser user) {
        return categories.findByUserIdOrderByNameAsc(user.getId()).stream()
                .map(c -> new LibraryViews.CategoryView(c.getId().toString(), c.getName())).toList();
    }

    @Transactional
    public LibraryViews.CategoryView createCategory(AppUser user, String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw ApiException.badRequest("Nome de categoria inválido.");
        }
        if (categories.existsByUserIdAndNameIgnoreCase(user.getId(), n)) {
            throw ApiException.conflict("Já existe uma categoria com esse nome.");
        }
        Category c = categories.save(new Category(user, n));
        return new LibraryViews.CategoryView(c.getId().toString(), c.getName());
    }

    @Transactional
    public void deleteCategory(AppUser user, UUID id) {
        Category c = categories.findByIdAndUserId(id, user.getId()).orElseThrow(() -> ApiException.notFound("Categoria não encontrada."));
        categories.delete(c);
    }

    private static Map<UUID, Long> toMap(List<Object[]> rows) {
        Map<UUID, Long> m = new HashMap<>();
        for (Object[] r : rows) {
            m.put((UUID) r[0], ((Number) r[1]).longValue());
        }
        return m;
    }

}
