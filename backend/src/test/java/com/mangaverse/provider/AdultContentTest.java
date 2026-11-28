package com.mangaverse.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdultContentTest {

    @Test
    void nsfwWarningOrAdultNameMarksTheSourceAsAdult() {
        assertThat(AdultContent.isAdult("Tia Manhwa", "eu.kanade.tachiyomi.extension.pt.tiamanhwa", "NSFW")).isTrue();
        assertThat(AdultContent.isAdult("Muito Hentai", "eu.kanade.tachiyomi.extension.pt.muitohentai", null)).isTrue();
        assertThat(AdultContent.isAdult("Quadrinhos de Sexo", null, "MIXED")).isTrue();
        assertThat(AdultContent.isAdult("X", "eu.kanade.tachiyomi.extension.all.nhentai", null)).isTrue();
    }

    @Test
    void mixedSourcesStayRegular() {
        assertThat(AdultContent.isAdult("Mugiwaras Oficial", "eu.kanade.tachiyomi.extension.pt.mugiwarasoficial", "MIXED")).isFalse();
        assertThat(AdultContent.isAdult("MangaDex", "eu.kanade.tachiyomi.extension.all.mangadex", "MIXED")).isFalse();
        assertThat(AdultContent.isAdult("HQ Now!", "eu.kanade.tachiyomi.extension.pt.hqnow", "SAFE")).isFalse();
    }
}
