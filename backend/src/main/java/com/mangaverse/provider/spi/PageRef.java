package com.mangaverse.provider.spi;

/** Referência a uma página obtida de um provedor. {@code url} é interpretada pelo próprio provedor. */
public record PageRef(int index, String url) {
}
