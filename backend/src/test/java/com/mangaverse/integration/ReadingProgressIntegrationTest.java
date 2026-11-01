package com.mangaverse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mangaverse.content.Chapter;
import com.mangaverse.content.Content;
import com.mangaverse.content.ContentSource;
import com.mangaverse.library.LibraryEntryRepository;
import com.mangaverse.library.LibraryStatus;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.reader.ReaderService;
import com.mangaverse.reader.ReadingProgressRepository;

class ReadingProgressIntegrationTest extends IntegrationTestBase {

    @Autowired ReaderService reader;
    @Autowired ReadingProgressRepository progress;
    @Autowired LibraryEntryRepository library;

    @Test
    void progressIsPersistedUpdatedAndCompletedOnLastPage() {
        Content c = newContent("Dandadan", ContentType.MANGA);
        ContentSource s = newSource(c, "x");
        Chapter ch1 = remoteChapter(c, s, "c1", "1");
        Chapter ch2 = remoteChapter(c, s, "c2", "2");

        reader.saveProgress(admin(), ch1.getId(), new ReaderService.ProgressRequest(4, 20, null));
        reader.saveProgress(admin(), ch1.getId(), new ReaderService.ProgressRequest(7, 20, null));

        var p = progress.findByUserIdAndChapterId(admin().getId(), ch1.getId()).orElseThrow();
        assertThat(p.getPage()).isEqualTo(7);
        assertThat(p.isCompleted()).isFalse();
        assertThat(progress.count()).isEqualTo(1);
        // Ler cria a entrada na biblioteca como "Lendo"
        assertThat(library.findByUserIdAndContentId(admin().getId(), c.getId()).orElseThrow().getStatus()).isEqualTo(LibraryStatus.READING);

        var resume = reader.continueReading(admin(), c.getId());
        assertThat(resume.chapterId()).isEqualTo(ch1.getId().toString());
        assertThat(resume.page()).isEqualTo(7);

        reader.saveProgress(admin(), ch1.getId(), new ReaderService.ProgressRequest(19, 20, null));
        assertThat(progress.findByUserIdAndChapterId(admin().getId(), ch1.getId()).orElseThrow().isCompleted()).isTrue();
        assertThat(reader.continueReading(admin(), c.getId()).chapterId()).isEqualTo(ch2.getId().toString());

        // Voltar a uma página anterior não desfaz a conclusão
        reader.saveProgress(admin(), ch1.getId(), new ReaderService.ProgressRequest(2, 20, null));
        assertThat(progress.findByUserIdAndChapterId(admin().getId(), ch1.getId()).orElseThrow().isCompleted()).isTrue();
    }

    @Test
    void markReadAndUnreadInBulk() {
        Content c = newContent("Frieren", ContentType.MANGA);
        ContentSource s = newSource(c, "y");
        Chapter a = remoteChapter(c, s, "a", "1");
        Chapter b = remoteChapter(c, s, "b", "2");
        reader.markRead(admin(), c.getId(), List.of(a.getId(), b.getId()), true);
        assertThat(progress.findByUserIdAndContentId(admin().getId(), c.getId())).hasSize(2).allMatch(x -> x.isCompleted());
        reader.markRead(admin(), c.getId(), List.of(b.getId()), false);
        assertThat(progress.findByUserIdAndContentId(admin().getId(), c.getId())).hasSize(1);
    }

    @Test
    void neighborsPreferSameLanguageVersion() {
        Content c = newContent("Multi", ContentType.MANGA);
        ContentSource s = newSource(c, "z");
        Chapter one = remoteChapter(c, s, "1pt", "1");
        Chapter twoEn = remoteChapter(c, s, "2en", "2");
        twoEn.setLanguage("en");
        chapters.save(twoEn);
        Chapter twoPt = remoteChapter(c, s, "2pt", "2");
        Chapter three = remoteChapter(c, s, "3pt", "3");

        var viewOne = chapters.findById(one.getId()).orElseThrow();
        assertThat(viewOne).isNotNull();
        var ordered = chapters.findByContentOrdered(c.getId());
        int idx = ordered.indexOf(ordered.stream().filter(x -> x.getId().equals(one.getId())).findFirst().orElseThrow());
        assertThat(com.mangaverse.reader.ReaderServiceAccess.pick(ordered, idx, 1, ordered.get(idx))).isEqualTo(twoPt.getId().toString());
        int idx2 = ordered.indexOf(ordered.stream().filter(x -> x.getId().equals(twoPt.getId())).findFirst().orElseThrow());
        assertThat(com.mangaverse.reader.ReaderServiceAccess.pick(ordered, idx2, 1, ordered.get(idx2))).isEqualTo(three.getId().toString());
        assertThat(com.mangaverse.reader.ReaderServiceAccess.pick(ordered, idx2, -1, ordered.get(idx2))).isEqualTo(one.getId().toString());
    }
}
