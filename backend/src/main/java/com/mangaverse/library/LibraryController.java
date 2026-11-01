package com.mangaverse.library;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.user.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/library")
public class LibraryController {

    private final LibraryService library;
    private final LocalImportService importer;
    private final DirectoryBrowser browser;
    private final CurrentUser currentUser;

    public LibraryController(LibraryService library, LocalImportService importer, DirectoryBrowser browser,
                             CurrentUser currentUser) {
        this.library = library;
        this.importer = importer;
        this.browser = browser;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<LibraryViews.LibraryItem> list(@RequestParam(required = false) ContentType type,
                                               @RequestParam(required = false) LibraryStatus status,
                                               @RequestParam(required = false) Boolean favorite,
                                               @RequestParam(required = false) Boolean monitored,
                                               @RequestParam(required = false) UUID categoryId,
                                               @RequestParam(required = false) String q) {
        return library.list(currentUser.get(), new LibraryService.Filter(type, status, favorite, categoryId, q, monitored));
    }

    @PostMapping
    public Map<String, String> add(@RequestBody LibraryService.AddRequest request) {
        LibraryEntry entry = library.add(currentUser.get(), request);
        return Map.of("entryId", entry.getId().toString(), "contentId", entry.getContent().getId().toString());
    }

    @PatchMapping("/{entryId}")
    public Map<String, String> update(@PathVariable UUID entryId, @RequestBody LibraryService.UpdateRequest request) {
        LibraryEntry entry = library.update(currentUser.get(), entryId, request);
        return Map.of("entryId", entry.getId().toString(), "status", entry.getStatus().name());
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> remove(@PathVariable UUID entryId) {
        library.remove(currentUser.get(), entryId);
        return ResponseEntity.noContent().build();
    }

    public record ImportRequest(@NotBlank @Size(max = 4096) String path, ContentType type) {
    }

    @PostMapping("/import")
    public LocalImportService.ImportResult importLocal(@Valid @RequestBody ImportRequest request) {
        return importer.importDirectory(currentUser.get(), request.path(), request.type());
    }

    @GetMapping("/browse")
    public DirectoryBrowser.Listing browse(@RequestParam(required = false) String path) {
        return browser.browse(path);
    }

    @GetMapping("/categories")
    public List<LibraryViews.CategoryView> categories() {
        return library.categories(currentUser.get());
    }

    public record CategoryRequest(@NotBlank @Size(max = 100) String name) {
    }

    @PostMapping("/categories")
    public LibraryViews.CategoryView createCategory(@Valid @RequestBody CategoryRequest request) {
        return library.createCategory(currentUser.get(), request.name());
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable UUID id) {
        library.deleteCategory(currentUser.get(), id);
        return ResponseEntity.noContent().build();
    }
}
