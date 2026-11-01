package com.mangaverse.reader;

import java.util.List;

import com.mangaverse.content.Chapter;

/** Expõe a escolha de capítulo vizinho (package-private) para os testes. */
public final class ReaderServiceAccess {

    private ReaderServiceAccess() {
    }

    public static String pick(List<Chapter> ordered, int idx, int step, Chapter current) {
        return ReaderService.pick(ordered, idx, step, current);
    }
}
