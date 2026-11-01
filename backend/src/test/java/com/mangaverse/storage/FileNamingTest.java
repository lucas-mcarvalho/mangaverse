package com.mangaverse.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class FileNamingTest {

    @Test
    void chapterFileNamesFollowLibraryLayout() {
        assertThat(FileNaming.chapterFileName(false, new BigDecimal("1"), null, "x")).isEqualTo("Capitulo 001.cbz");
        assertThat(FileNaming.chapterFileName(false, new BigDecimal("10.5"), null, "x")).isEqualTo("Capitulo 010.5.cbz");
        assertThat(FileNaming.chapterFileName(true, new BigDecimal("2"), null, "x")).isEqualTo("Edicao 002.cbz");
        assertThat(FileNaming.chapterFileName(false, new BigDecimal("1200"), null, "x")).isEqualTo("Capitulo 1200.cbz");
        assertThat(FileNaming.chapterFileName(false, null, "Oneshot: Especial", "abc")).isEqualTo("Capitulo - Oneshot Especial.cbz");
    }

    @Test
    void sanitizeRemovesInvalidCharactersAndReservedNames() {
        assertThat(FileNaming.sanitize("Re:Zero / Kara <Hajimeru>?")).isEqualTo("Re Zero Kara Hajimeru");
        assertThat(FileNaming.sanitize("..")).isEqualTo("_");
        assertThat(FileNaming.sanitize("CON")).isEqualTo("_CON");
        assertThat(FileNaming.sanitize("  título.  ")).isEqualTo("título");
        assertThat(FileNaming.sanitize("a".repeat(300))).hasSize(120);
    }

    @Test
    void parsesChapterAndVolumeNumbers() {
        assertThat(FileNaming.parseNumber("Capitulo 012.cbz")).isEqualByComparingTo("12");
        assertThat(FileNaming.parseNumber("One Piece - Ch. 1050.5.cbz")).isEqualByComparingTo("1050.5");
        assertThat(FileNaming.parseNumber("Batman #003 (2025).cbr")).isEqualByComparingTo("3");
        assertThat(FileNaming.parseNumber("Berserk v01 c003.cbz")).isEqualByComparingTo("3");
        assertThat(FileNaming.parseNumber("Edição 7.pdf")).isEqualByComparingTo("7");
        assertThat(FileNaming.parseVolume("Berserk v01 c003.cbz")).isEqualTo("1");
        assertThat(FileNaming.parseVolume("Capitulo 5.cbz")).isNull();
    }
}
