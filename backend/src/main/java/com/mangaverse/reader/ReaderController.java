package com.mangaverse.reader;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.storage.archive.PageData;
import com.mangaverse.user.CurrentUser;

@RestController
@RequestMapping("/api/reader")
public class ReaderController {

    private final ReaderService reader;
    private final CurrentUser currentUser;

    public ReaderController(ReaderService reader, CurrentUser currentUser) {
        this.reader = reader;
        this.currentUser = currentUser;
    }

    @GetMapping("/chapters/{chapterId}")
    public ReaderService.ReaderChapterView open(@PathVariable UUID chapterId) {
        return reader.open(chapterId, currentUser.get());
    }

    @GetMapping("/chapters/{chapterId}/pages/{index}")
    public ResponseEntity<byte[]> page(@PathVariable UUID chapterId, @PathVariable int index) {
        PageData data = reader.page(chapterId, index);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(data.contentType()))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(data.data());
    }

    @PutMapping("/chapters/{chapterId}/progress")
    public ResponseEntity<Void> progress(@PathVariable UUID chapterId, @RequestBody ReaderService.ProgressRequest request) {
        reader.saveProgress(currentUser.get(), chapterId, request);
        return ResponseEntity.noContent().build();
    }
}
