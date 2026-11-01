package com.mangaverse.download;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/downloads")
public class DownloadController {

    private final DownloadService service;

    public DownloadController(DownloadService service) {
        this.service = service;
    }

    @GetMapping
    public List<DownloadService.DownloadView> list(@RequestParam(defaultValue = "200") int limit) {
        return service.list(limit);
    }

    @GetMapping("/summary")
    public DownloadService.Summary summary() {
        return service.summary();
    }

    public record EnqueueRequest(@NotEmpty @Size(max = 2000) List<UUID> chapterIds) {
    }

    @PostMapping
    public DownloadService.EnqueueResult enqueue(@Valid @RequestBody EnqueueRequest request) {
        return service.enqueue(request.chapterIds(), false);
    }

    public record ContentRequest(String volume, Boolean onlyMissing) {
    }

    @PostMapping("/content/{contentId}")
    public DownloadService.EnqueueResult enqueueContent(@PathVariable UUID contentId, @RequestBody(required = false) ContentRequest request) {
        String volume = request == null ? null : request.volume();
        boolean onlyMissing = request == null || request.onlyMissing() == null || request.onlyMissing();
        return service.enqueueContent(contentId, volume, onlyMissing);
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pause(@PathVariable UUID id) {
        service.pause(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resume(@PathVariable UUID id) {
        service.resume(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancel(@PathVariable UUID id) {
        service.cancel(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable UUID id) {
        service.retry(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/finished")
    public Map<String, Integer> clearFinished() {
        return Map.of("removed", service.clearFinished());
    }

    @PostMapping("/verify")
    public DownloadService.IntegrityReport verify() {
        return service.verifyAll();
    }
}
