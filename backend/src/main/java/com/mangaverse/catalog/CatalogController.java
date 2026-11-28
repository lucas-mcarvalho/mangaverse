package com.mangaverse.catalog;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.provider.spi.Genre;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/catalog")
@Validated
public class CatalogController {

    private final CatalogService service;

    public CatalogController(CatalogService service) {
        this.service = service;
    }

    @GetMapping("/sources")
    public List<CatalogService.CatalogSource> sources() {
        return service.sources();
    }

    @GetMapping("/genres")
    public List<Genre> genres(@RequestParam @Size(max = 120) String provider) {
        return service.genres(provider);
    }

    @GetMapping("/listing")
    public CatalogService.Listing listing(@RequestParam @Size(max = 120) String provider,
                                          @RequestParam(defaultValue = "POPULAR") CatalogService.Kind kind,
                                          @RequestParam(required = false) @Size(max = 64) String genre,
                                          @RequestParam(defaultValue = "1") @Min(1) @Max(100) int page) {
        return service.listing(provider, kind, genre, page);
    }
}
