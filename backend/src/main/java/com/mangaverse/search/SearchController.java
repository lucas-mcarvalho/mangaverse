package com.mangaverse.search;

import java.util.List;
import java.util.Set;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.common.ApiException;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderService;
import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.DiscoverKind;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.settings.SettingsService;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api")
@Validated
public class SearchController {

    private final SearchService searchService;
    private final ProviderRegistry registry;
    private final ProviderService providerService;
    private final SettingsService settings;

    public SearchController(SearchService searchService, ProviderRegistry registry, ProviderService providerService,
                            SettingsService settings) {
        this.searchService = searchService;
        this.registry = registry;
        this.providerService = providerService;
        this.settings = settings;
    }

    @GetMapping("/search")
    public SearchService.SearchResponse search(@RequestParam("q") @NotBlank @Size(max = 200) String query,
                                               @RequestParam(value = "types", required = false) Set<ContentType> types,
                                               @RequestParam(value = "providers", required = false) Set<String> providers) {
        return searchService.search(query, types, providers);
    }

    public record DiscoverProvider(String code, String name) {
    }

    public record DiscoverResponse(String provider, DiscoverKind kind, int page, List<RemoteTitle> items,
                                   List<DiscoverProvider> providers) {
    }

    @GetMapping("/explore")
    public DiscoverResponse explore(@RequestParam(defaultValue = "mangadex") String provider,
                                    @RequestParam(defaultValue = "POPULAR") DiscoverKind kind,
                                    @RequestParam(defaultValue = "1") @Min(1) @Max(50) int page) {
        List<ContentProvider> available = registry.discoverable();
        ContentProvider selected = available.stream().filter(p -> p.code().equals(provider)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Provedor indisponível para exploração: " + provider));
        List<RemoteTitle> items = providerService.discover(selected, kind, Set.copyOf(settings.languages().subList(0, 1)), page);
        return new DiscoverResponse(selected.code(), kind, page, items,
                available.stream().map(p -> new DiscoverProvider(p.code(), p.displayName())).toList());
    }
}
