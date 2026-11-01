package com.mangaverse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mangaverse.content.ContentQueryService;
import com.mangaverse.content.SourceFinderService;
import com.mangaverse.library.LibraryEntry;
import com.mangaverse.library.LibraryService;
import com.mangaverse.provider.Provider;
import com.mangaverse.provider.ProviderType;
import com.mangaverse.reader.ReaderService;

class SourceFinderIntegrationTest extends IntegrationTestBase {

    @Autowired LibraryService library;
    @Autowired SourceFinderService finder;
    @Autowired ContentQueryService query;
    @Autowired ReaderService reader;

    FakeProvider br;

    @BeforeEach
    void secondSource() {
        if (providers.findByCode("suwayomi:42").isEmpty()) {
            Provider p = new Provider("suwayomi:42", "Fonte BR", ProviderType.SUWAYOMI_SOURCE);
            p.setExternalRef("42");
            p.setLanguage("pt-br");
            providers.save(p);
        }
        br = new FakeProvider("suwayomi:42");
        when(registry.require("suwayomi:42")).thenReturn(br);
        when(registry.find("suwayomi:42")).thenReturn(Optional.of(br));
        when(registry.searchable()).thenReturn(List.of(fake, br));
    }

    @Test
    void fillsOfficialOnlyChaptersFromAnotherInstalledSource() {
        fake.searchId = "abc";
        fake.searchTitle = "Título abc";
        fake.chapters.add(FakeProvider.chapter("md1", "1"));
        fake.chapters.add(FakeProvider.officialOnly("md2", "2"));
        LibraryEntry entry = library.add(admin(), new LibraryService.AddRequest("mangadex", "abc", null, null, null, null, null));
        var contentId = entry.getContent().getId();

        var before = query.view(contentId, admin());
        assertThat(before.officialOnlyCount()).isEqualTo(1);
        // O leitor pula o capítulo que só existe no site oficial
        var ch1 = before.chapters().stream().filter(c -> "md1".equals(c.externalId())).findFirst().orElseThrow();
        assertThat(reader.open(java.util.UUID.fromString(ch1.id()), admin()).nextChapterId()).isNull();

        br.searchId = "br9";
        br.searchTitle = "Titulo ABC"; // acentos/caixa diferentes: mesma obra
        br.chapters.add(FakeProvider.chapter("br-2", "2"));
        br.chapters.add(FakeProvider.chapter("br-3", "3"));

        var result = finder.findMoreSources(contentId);

        assertThat(result.linked()).containsExactly("Fake suwayomi:42");
        assertThat(result.newChapters()).isEqualTo(2);
        var after = query.view(contentId, admin());
        assertThat(after.officialOnlyCount()).isZero();
        assertThat(after.sources()).hasSize(2);
        var next = reader.open(java.util.UUID.fromString(ch1.id()), admin()).nextChapterId();
        assertThat(after.chapters().stream().filter(c -> c.id().equals(next)).findFirst().orElseThrow().externalId()).isEqualTo("br-2");

        // Rodar de novo não duplica fontes
        assertThat(finder.findMoreSources(contentId).linked()).isEmpty();
        assertThat(sources.count()).isEqualTo(2);
    }

    @Test
    void skipsSourcesThatOnlyDuplicateExistingChapters() {
        fake.searchId = "abc";
        fake.searchTitle = "Título abc";
        fake.chapters.add(FakeProvider.chapter("md1", "1"));
        fake.chapters.add(FakeProvider.officialOnly("md2", "2"));
        library.add(admin(), new LibraryService.AddRequest("mangadex", "abc", null, null, null, null, null));
        br.searchId = "espelho";
        br.searchTitle = "Título abc";
        br.chapters.add(FakeProvider.chapter("e1", "1")); // mesma numeração, nada novo

        var result = finder.findMoreSources(contents.findAll().get(0).getId());

        assertThat(result.linked()).isEmpty();
        assertThat(result.skipped()).containsExactly("Fake suwayomi:42");
        assertThat(sources.count()).isEqualTo(1);
    }

    @Test
    void doesNotLinkDifferentWorks() {
        fake.searchId = "abc";
        fake.searchTitle = "Título abc";
        library.add(admin(), new LibraryService.AddRequest("mangadex", "abc", null, null, null, null, null));
        br.searchId = "outro";
        br.searchTitle = "Título abc Gaiden";
        var contentId = contents.findAll().get(0).getId();

        assertThat(finder.findMoreSources(contentId).linked()).isEmpty();
        assertThat(sources.count()).isEqualTo(1);
    }
}
