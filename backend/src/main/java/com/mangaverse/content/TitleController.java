package com.mangaverse.content;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ProviderCapabilities;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitleDetails;
import com.mangaverse.settings.SettingsService;

/** Detalhes de um título diretamente do provedor, antes de adicioná-lo à biblioteca. */
@RestController
@RequestMapping("/api/titles")
public class TitleController {

    private final ProviderRegistry registry;
    private final ProviderService providerService;
    private final ContentSourceRepository sources;
    private final SettingsService settings;

    public TitleController(ProviderRegistry registry, ProviderService providerService, ContentSourceRepository sources,
                           SettingsService settings) {
        this.registry = registry;
        this.providerService = providerService;
        this.sources = sources;
        this.settings = settings;
    }

    public record RemoteTitleView(String providerCode, String providerName, RemoteTitleDetails details,
                                  List<RemoteChapter> chapters, String chaptersError, ProviderCapabilities capabilities,
                                  String libraryContentId, List<String> languages) {
    }

    @GetMapping("/{providerCode}/{externalId}")
    public RemoteTitleView get(@PathVariable String providerCode, @PathVariable String externalId,
                               @RequestParam(value = "lang", required = false) Set<String> lang) {
        ContentProvider provider = registry.require(providerCode);
        RemoteTitleDetails details = providerService.details(providerCode, externalId);
        Set<String> languages = lang == null || lang.isEmpty() ? new LinkedHashSet<>(settings.languages()) : lang;
        List<RemoteChapter> chapters = List.of();
        String chaptersError = null;
        if (provider.capabilities().chapters()) {
            try {
                chapters = providerService.chapters(providerCode, externalId, languages);
            } catch (ProviderException e) {
                chaptersError = e.getMessage();
            }
        }
        String contentId = sources.findByProviderCodeAndExternalId(providerCode, externalId)
                .map(s -> s.getContent().getId().toString()).orElse(null);
        return new RemoteTitleView(providerCode, provider.displayName(), details, chapters, chaptersError,
                provider.capabilities(), contentId, List.copyOf(languages));
    }
}
