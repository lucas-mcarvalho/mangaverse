package com.mangaverse.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mangaverse.content.Chapter;
import com.mangaverse.content.ChapterRepository;
import com.mangaverse.content.Content;
import com.mangaverse.download.DownloadService;
import com.mangaverse.library.LibraryService;
import com.mangaverse.library.LibraryViews;
import com.mangaverse.monitor.UpdateHistoryRepository;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.reader.ReadingProgress;
import com.mangaverse.reader.ReadingProgressRepository;
import com.mangaverse.user.AppUser;
import com.mangaverse.user.CurrentUser;

class DashboardControllerTest {
    @Test void removedTitlesDoNotReappearFromReadingHistory() {
        var user = mock(AppUser.class);
        when(user.getId()).thenReturn(UUID.randomUUID());
        var current = mock(CurrentUser.class);
        when(current.get()).thenReturn(user);
        var progress = mock(ReadingProgressRepository.class);
        var library = mock(LibraryService.class);
        var history = mock(UpdateHistoryRepository.class);
        var downloads = mock(DownloadService.class);
        var chapters = mock(ChapterRepository.class);
        UUID keptId = UUID.randomUUID();
        UUID removedId = UUID.randomUUID();
        var item = mock(LibraryViews.LibraryItem.class);
        when(item.contentId()).thenReturn(keptId.toString());
        when(item.entryId()).thenReturn("entry-1");
        when(item.type()).thenReturn(ContentType.COMIC);
        when(item.status()).thenReturn("READING");
        when(library.list(eq(user), any())).thenReturn(List.of(item));
        when(history.findRecentByType(any(), any())).thenReturn(List.of());
        when(downloads.list(50)).thenReturn(List.of());
        var recent = List.of(reading(removedId), reading(keptId));
        when(progress.findRecent(eq(user.getId()), any())).thenReturn(recent);

        var dashboard = new DashboardController(current, progress, library, history, downloads, chapters).get();

        assertThat(dashboard.continueReading()).hasSize(1);
        assertThat(dashboard.continueReading().getFirst().contentId()).isEqualTo(keptId.toString());
        assertThat(dashboard.continueReading().getFirst().entryId()).isEqualTo("entry-1");
        assertThat(dashboard.stats().titles()).isEqualTo(1);
    }

    private ReadingProgress reading(UUID contentId) {
        var content = mock(Content.class);
        when(content.getId()).thenReturn(contentId);
        when(content.getType()).thenReturn(ContentType.COMIC);
        when(content.getTitle()).thenReturn("Batman");
        var chapter = mock(Chapter.class);
        when(chapter.getId()).thenReturn(UUID.randomUUID());
        when(chapter.displayLabel(true)).thenReturn("Edição 1");
        var progress = mock(ReadingProgress.class);
        when(progress.getContent()).thenReturn(content);
        when(progress.getChapter()).thenReturn(chapter);
        return progress;
    }
}
