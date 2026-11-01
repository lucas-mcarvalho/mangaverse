package com.mangaverse.provider.spi;

/**
 * Recursos realmente oferecidos por um provedor. Nem todo provedor permite leitura ou download.
 */
public record ProviderCapabilities(boolean search, boolean chapters, boolean read, boolean download,
                                   boolean metadataOnly, boolean discover) {

    public static ProviderCapabilities metadata() {
        return new ProviderCapabilities(true, true, false, false, true, false);
    }
}
