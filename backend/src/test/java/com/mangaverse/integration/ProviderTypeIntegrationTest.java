package com.mangaverse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mangaverse.common.ApiException;
import com.mangaverse.content.Content;
import com.mangaverse.content.ContentSource;
import com.mangaverse.provider.Provider;
import com.mangaverse.provider.ProviderType;
import com.mangaverse.provider.SourceManagementService;
import com.mangaverse.provider.spi.ContentType;

class ProviderTypeIntegrationTest extends IntegrationTestBase {

    @Autowired SourceManagementService service;

    private Provider comicSource() {
        return providers.findByCode("suwayomi:900").orElseGet(() -> {
            Provider p = new Provider("suwayomi:900", "HQ Now!", ProviderType.SUWAYOMI_SOURCE);
            p.setExternalRef("900");
            return providers.save(p);
        });
    }

    @Test
    void settingComicTypeConvertsTitlesImportedOnlyFromThatSource() {
        Provider hq = comicSource();
        Content onlyHq = newContent("Batman - A Piada Mortal", ContentType.MANGA);
        sources.save(new ContentSource(onlyHq, hq, "181"));
        Content shared = newContent("Batman: Death Mask", ContentType.MANGA);
        sources.save(new ContentSource(shared, hq, "182"));
        newSource(shared, "md-death-mask"); // também vem do MangaDex: não deve ser alterado

        Provider updated = service.update(hq.getId(), null, null, "COMIC");

        assertThat(updated.forcedType()).contains(ContentType.COMIC);
        assertThat(contents.findById(onlyHq.getId()).orElseThrow().getType()).isEqualTo(ContentType.COMIC);
        assertThat(contents.findById(shared.getId()).orElseThrow().getType()).isEqualTo(ContentType.MANGA);

        service.update(hq.getId(), null, null, "AUTO");
        assertThat(providers.findById(hq.getId()).orElseThrow().forcedType()).isEmpty();
    }

    @Test
    void rejectsInvalidTypeAndNonSuwayomiProviders() {
        Provider hq = comicSource();
        assertThatThrownBy(() -> service.update(hq.getId(), null, null, "NOVELA")).isInstanceOf(ApiException.class);
        Provider mangadex = providers.findByCode("mangadex").orElseThrow();
        assertThatThrownBy(() -> service.update(mangadex.getId(), null, null, "COMIC")).isInstanceOf(ApiException.class);
    }
}
