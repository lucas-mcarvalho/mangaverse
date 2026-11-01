package com.mangaverse.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mangaverse.common.ApiException;
import com.mangaverse.content.Content;
import com.mangaverse.library.LibraryEntry;
import com.mangaverse.library.LibraryService;
import com.mangaverse.library.LibraryStatus;
import com.mangaverse.library.LibraryViews;
import com.mangaverse.provider.spi.ContentType;

class LibraryIntegrationTest extends IntegrationTestBase {

    @Autowired LibraryService library;

    @Test
    void addFromProviderImportsContentChaptersAndIsIdempotent() {
        fake.chapters.add(FakeProvider.chapter("c1", "1"));
        fake.chapters.add(FakeProvider.chapter("c2", "2"));

        LibraryEntry first = library.add(admin(), new LibraryService.AddRequest("mangadex", "abc", null, null, true, false, null));
        LibraryEntry second = library.add(admin(), new LibraryService.AddRequest("mangadex", "abc", null, null, null, null, null));

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(contents.count()).isEqualTo(1);
        assertThat(sources.count()).isEqualTo(1);
        assertThat(chapters.count()).isEqualTo(2);
        assertThat(first.isMonitored()).isTrue();
        assertThat(first.getStatus()).isEqualTo(LibraryStatus.PLAN_TO_READ);
    }

    @Test
    void sameTitleFromAnotherProviderIsMergedIntoExistingContent() {
        library.add(admin(), new LibraryService.AddRequest("mangadex", "abc", null, null, null, null, null));
        Content existing = contents.findAll().get(0);
        assertThat(existing.getTitle()).isEqualTo("Título abc");
        // Outro ID no mesmo provedor nunca é mesclado (são obras diferentes na fonte)
        library.add(admin(), new LibraryService.AddRequest("mangadex", "xyz", null, null, null, null, null));
        assertThat(contents.count()).isEqualTo(2);
    }

    @Test
    void updateStatusFavoriteCategoryAndFilters() {
        Content c = newContent("Berserk", ContentType.MANGA);
        Content hq = newContent("Invincible", ContentType.COMIC);
        LibraryEntry e1 = library.addContent(admin(), c.getId(), null);
        library.addContent(admin(), hq.getId(), null);
        LibraryViews.CategoryView cat = library.createCategory(admin(), "Seinen");

        library.update(admin(), e1.getId(), new LibraryService.UpdateRequest(LibraryStatus.READING, true, true, false, UUID.fromString(cat.id()), null));

        var reading = library.list(admin(), new LibraryService.Filter(null, LibraryStatus.READING, null, null, null, null));
        assertThat(reading).singleElement().satisfies(i -> {
            assertThat(i.title()).isEqualTo("Berserk");
            assertThat(i.favorite()).isTrue();
            assertThat(i.categoryName()).isEqualTo("Seinen");
        });
        assertThat(library.list(admin(), new LibraryService.Filter(ContentType.COMIC, null, null, null, null, null)))
                .extracting(LibraryViews.LibraryItem::title).containsExactly("Invincible");
        assertThat(library.list(admin(), new LibraryService.Filter(null, null, null, null, "berse", null))).hasSize(1);

        assertThatThrownBy(() -> library.createCategory(admin(), "seinen")).isInstanceOf(ApiException.class);
        library.update(admin(), e1.getId(), new LibraryService.UpdateRequest(null, null, null, null, null, true));
        assertThat(library.list(admin(), new LibraryService.Filter(null, null, null, null, "berserk", null)).get(0).categoryId()).isNull();
    }

    @Test
    void removeDeletesEntryButKeepsContent() {
        Content c = newContent("One Piece", ContentType.MANGA);
        LibraryEntry e = library.addContent(admin(), c.getId(), null);
        library.remove(admin(), e.getId());
        assertThat(library.list(admin(), new LibraryService.Filter(null, null, null, null, null, null))).isEmpty();
        assertThat(contents.existsById(c.getId())).isTrue();
        assertThatThrownBy(() -> library.remove(admin(), e.getId())).isInstanceOf(ApiException.class);
    }

    @Test
    void databaseRejectsDuplicateEntriesForSameUserAndContent() {
        Content c = newContent("Dup", ContentType.MANGA);
        library.addContent(admin(), c.getId(), null);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO library_entries (id, user_id, content_id) VALUES (?, ?, ?)",
                UUID.randomUUID(), admin().getId(), c.getId()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
