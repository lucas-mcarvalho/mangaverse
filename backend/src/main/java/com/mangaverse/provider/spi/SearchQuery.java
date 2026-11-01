package com.mangaverse.provider.spi;

import java.util.Set;

/**
 * @param languages idiomas desejados em ordem de preferência (ex.: pt-br, en)
 * @param types     tipos permitidos; vazio significa todos
 */
public record SearchQuery(String text, Set<String> languages, Set<ContentType> types, int limit) {
}
