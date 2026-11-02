package com.mangaverse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mangaverse.content.ContentService;
import com.mangaverse.download.DownloadRepository;
import com.mangaverse.download.DownloadStatus;
import com.mangaverse.library.LibraryEntry;
import com.mangaverse.library.LibraryService;
import com.mangaverse.monitor.MonitorService;
import com.mangaverse.monitor.UpdateHistory;
import com.mangaverse.monitor.UpdateHistoryRepository;
import com.mangaverse.settings.SettingsService;

class MonitorIntegrationTest extends IntegrationTestBase {

    @Autowired LibraryService library;
    @Autowired MonitorService monitor;
    @Autowired ContentService contentService;
    @Autowired UpdateHistoryRepository history;
    @Autowired DownloadRepository downloads;
    @Autowired SettingsService settings;

    @Test
    void detectsNewChaptersCreatesNotificationsWithoutDuplicates() {
        fake.chapters.add(FakeProvider.chapter("c1", "1"));
        LibraryEntry entry = library.add(admin(), new LibraryService.AddRequest("mangadex", "t", null, null, true, false, null));
        assertThat(history.count()).isZero(); // importação inicial não gera notificações

        fake.chapters.add(FakeProvider.chapter("c2", "2"));
        var result = monitor.runAll();

        assertThat(result.contentsChecked()).isEqualTo(1);
        assertThat(result.newChapters()).isEqualTo(1);
        assertThat(chapters.countByContentId(entry.getContent().getId())).isEqualTo(2);
        List<UpdateHistory> events = history.findAll();
        assertThat(events).singleElement().satisfies(h -> {
            assertThat(h.getEventType()).isEqualTo(UpdateHistory.EventType.NEW_CHAPTER);
            assertThat(h.getMessage()).contains("Capítulo 2");
        });
        assertThat(history.countBySeenFalse()).isEqualTo(1);
        assertThat(downloads.count()).isZero(); // download automático desligado

        // Nova verificação sem novidades não duplica registros
        assertThat(monitor.runAll().newChapters()).isZero();
        assertThat(history.count()).isEqualTo(1);
        assertThat(settings.monitorLastRunAt()).isNotNull();
    }

    @Test
    void autoDownloadQueuesOnlyWhenEnabledGloballyAndPerTitle() {
        fake.chapters.add(FakeProvider.chapter("c1", "1"));
        LibraryEntry entry = library.add(admin(), new LibraryService.AddRequest("mangadex", "auto", null, null, true, true, null));

        fake.chapters.add(FakeProvider.chapter("c2", "2"));
        assertThat(monitor.checkContent(entry.getContent().getId(), true).autoQueued()).isZero();

        settings.update(new SettingsService.SettingsUpdate(null, null, null, true, null, null, null, null, null));
        fake.chapters.add(FakeProvider.chapter("c3", "3"));
        var result = monitor.checkContent(entry.getContent().getId(), true);

        assertThat(result.newChapters()).isEqualTo(1);
        assertThat(result.autoQueued()).isEqualTo(1);
        assertThat(downloads.findAll()).singleElement().satisfies(d -> {
            assertThat(d.isAutoTriggered()).isTrue();
            assertThat(d.getStatus()).isEqualTo(DownloadStatus.QUEUED);
        });
    }

    @Test
    void unavailableSourceIsRecordedOnceAndDoesNotBreakOtherTitles() {
        fake.chapters.add(FakeProvider.chapter("c1", "1"));
        library.add(admin(), new LibraryService.AddRequest("mangadex", "x", null, null, true, false, null));
        org.mockito.Mockito.when(registry.require("mangadex")).thenThrow(new com.mangaverse.provider.spi.ProviderException("mangadex", "offline"));

        var r1 = monitor.runAll();
        var r2 = monitor.runAll();

        assertThat(r1.errors()).hasSize(1);
        assertThat(r2.errors()).hasSize(1);
        assertThat(history.findAll()).filteredOn(h -> h.getEventType() == UpdateHistory.EventType.CHECK_FAILED).hasSize(1);
    }

    @Test
    void localChapterWithSameNumberIsLinkedInsteadOfDuplicated() {
        var content = newContent("Local + remoto", com.mangaverse.provider.spi.ContentType.MANGA);
        var local = new com.mangaverse.content.Chapter(content, null, null);
        local.setLocalPath(STORAGE.resolve("x/Capitulo 001.cbz").toString());
        local.setNumber(new java.math.BigDecimal("1"));
        chapters.save(local);
        var source = newSource(content, "lr");
        fake.chapters.add(FakeProvider.chapter("r1", "1"));
        fake.chapters.add(FakeProvider.chapter("r2", "2"));

        var created = contentService.syncChapters(source.getId(), true);

        assertThat(created).hasSize(1);
        assertThat(chapters.countByContentId(content.getId())).isEqualTo(2);
        var linked = chapters.findById(local.getId()).orElseThrow();
        assertThat(linked.getExternalId()).isEqualTo("r1");
    }
}
