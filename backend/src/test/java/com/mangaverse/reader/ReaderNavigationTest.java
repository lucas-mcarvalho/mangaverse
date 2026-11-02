package com.mangaverse.reader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.mangaverse.content.Chapter;
import com.mangaverse.content.ContentSource;

class ReaderNavigationTest {
    private ContentSource source() {
        ContentSource source = mock(ContentSource.class);
        when(source.getId()).thenReturn(UUID.randomUUID());
        return source;
    }

    private Chapter chapter(int number, ContentSource source, String language) {
        Chapter chapter = mock(Chapter.class);
        when(chapter.getId()).thenReturn(UUID.randomUUID());
        when(chapter.getNumber()).thenReturn(BigDecimal.valueOf(number));
        when(chapter.getSource()).thenReturn(source);
        when(chapter.getLanguage()).thenReturn(language);
        return chapter;
    }

    @Test
    void nextAndPreviousKeepChosenSourceWhenOtherSourceHasSameLanguageAndGroup() {
        ContentSource xoxo = source();
        ContentSource readComics = source();
        Chapter oneXoxo = chapter(1, xoxo, "en");
        Chapter oneRead = chapter(1, readComics, "en");
        Chapter twoXoxo = chapter(2, xoxo, "en");
        Chapter twoRead = chapter(2, readComics, "en");
        List<Chapter> ordered = List.of(oneXoxo, oneRead, twoXoxo, twoRead);

        assertThat(ReaderService.pick(ordered, 1, 1, oneRead)).isEqualTo(twoRead.getId().toString());
        assertThat(ReaderService.pick(ordered, 3, -1, twoRead)).isEqualTo(oneRead.getId().toString());
        assertThat(ReaderService.pick(ordered, 0, 1, oneXoxo)).isEqualTo(twoXoxo.getId().toString());
    }

    @Test
    void missingEditionFallsBackToSameLanguageInAnotherSource() {
        Chapter one = chapter(1, source(), "en");
        Chapter twoPt = chapter(2, source(), "pt-br");
        Chapter twoEn = chapter(2, source(), "en");

        assertThat(ReaderService.pick(List.of(one, twoPt, twoEn), 0, 1, one)).isEqualTo(twoEn.getId().toString());
    }
}
