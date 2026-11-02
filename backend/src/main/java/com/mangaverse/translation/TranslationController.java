package com.mangaverse.translation;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.user.CurrentUser;

@RestController
@RequestMapping("/api/translations/chapters/{chapterId}")
public class TranslationController {
    private final TranslationService service;
    private final CurrentUser user;

    public TranslationController(TranslationService service, CurrentUser user) {
        this.service = service;
        this.user = user;
    }

    @GetMapping
    public TranslationService.View status(@PathVariable UUID chapterId) { return service.status(chapterId); }

    @PostMapping
    public ResponseEntity<TranslationService.View> start(@PathVariable UUID chapterId) {
        return ResponseEntity.accepted().body(service.start(chapterId, user.get()));
    }

    @PostMapping("/redo")
    public ResponseEntity<TranslationService.View> redo(@PathVariable UUID chapterId) {
        return ResponseEntity.accepted().body(service.redo(chapterId, user.get()));
    }

    @DeleteMapping
    public TranslationService.View cancel(@PathVariable UUID chapterId) { return service.cancel(chapterId); }

    @GetMapping("/pages/{index}")
    public ResponseEntity<byte[]> page(@PathVariable UUID chapterId, @PathVariable int index) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff").body(service.page(chapterId, index).data());
    }
}
