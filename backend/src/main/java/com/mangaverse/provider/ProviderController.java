package com.mangaverse.provider;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.provider.spi.ProviderHealth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
public class ProviderController {

    private final SourceManagementService service;

    public ProviderController(SourceManagementService service) {
        this.service = service;
    }

    public record ProviderView(String id, String code, String name, String type, boolean enabled, int priority,
                               String language, String iconUrl, boolean supportsSearch, boolean supportsDownload,
                               boolean metadataOnly, String lastStatus, String lastError, Instant lastCheckedAt) {
        static ProviderView of(Provider p) {
            return new ProviderView(p.getId().toString(), p.getCode(), p.getName(), p.getType().name(), p.isEnabled(),
                    p.getPriority(), p.getLanguage(), p.getIconUrl(), p.isSupportsSearch(), p.isSupportsDownload(),
                    p.isMetadataOnly(), p.getLastStatus(), p.getLastError(), p.getLastCheckedAt());
        }
    }

    @GetMapping("/providers")
    public List<ProviderView> providers() {
        return service.listProviders().stream().map(ProviderView::of).toList();
    }

    public record ProviderUpdate(Boolean enabled, Integer priority) {
    }

    @PatchMapping("/providers/{id}")
    public ProviderView update(@PathVariable UUID id, @RequestBody ProviderUpdate body) {
        return ProviderView.of(service.update(id, body.enabled(), body.priority()));
    }

    @PostMapping("/providers/health")
    public Map<String, ProviderHealth> health() {
        return service.checkHealth();
    }

    @GetMapping("/sources/suwayomi")
    public SourceManagementService.SuwayomiStatus suwayomiStatus() {
        return service.suwayomiStatus();
    }

    @PostMapping("/sources/suwayomi/sync")
    public List<ProviderView> sync() {
        return service.syncSuwayomiSources().stream().map(ProviderView::of).toList();
    }

    @GetMapping("/sources/suwayomi/extensions")
    public List<SourceManagementService.ExtensionView> extensions(@RequestParam(required = false) String lang,
                                                                  @RequestParam(required = false) Boolean installed,
                                                                  @RequestParam(required = false) String q) {
        return service.extensions(lang, installed, q);
    }

    @PostMapping("/sources/suwayomi/extensions/refresh")
    public List<SourceManagementService.ExtensionView> refresh() {
        return service.refreshExtensions();
    }

    @PostMapping("/sources/suwayomi/extensions/{pkgName}/{action}")
    public Map<String, String> changeExtension(@PathVariable String pkgName, @PathVariable String action) {
        service.changeExtension(pkgName, action);
        return Map.of("status", "ok");
    }

    @GetMapping("/sources/suwayomi/stores")
    public List<SourceManagementService.StoreView> stores() {
        return service.stores();
    }

    public record StoreRequest(@NotBlank @Size(max = 500) String indexUrl) {
    }

    @PostMapping("/sources/suwayomi/stores")
    public List<SourceManagementService.StoreView> addStore(@Valid @RequestBody StoreRequest body) {
        return service.addStore(body.indexUrl());
    }

    @DeleteMapping("/sources/suwayomi/stores")
    public List<SourceManagementService.StoreView> removeStore(@RequestParam String indexUrl) {
        return service.removeStore(indexUrl);
    }
}
