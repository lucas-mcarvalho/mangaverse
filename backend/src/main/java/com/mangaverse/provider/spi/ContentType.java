package com.mangaverse.provider.spi;

public enum ContentType {
    MANGA, MANHWA, MANHUA, COMIC;

    /** Deduz o tipo a partir do idioma original (códigos ISO usados pelo MangaDex). */
    public static ContentType fromOriginalLanguage(String lang) {
        if (lang == null) {
            return MANGA;
        }
        return switch (lang.toLowerCase()) {
            case "ko" -> MANHWA;
            case "zh", "zh-hk", "zh-ro" -> MANHUA;
            case "en" -> COMIC;
            default -> MANGA;
        };
    }

    public boolean isComic() {
        return this == COMIC;
    }
}
