package com.mangaverse.content;

import java.util.UUID;

/** Converte referências de capa armazenadas em URLs servidas pela API. */
public final class Covers {

    public static final String LOCAL = "local:";

    private Covers() {
    }

    /** Capas locais são servidas por /api/contents/{id}/cover; as demais são resolvidas pelo frontend via /api/images. */
    public static String resolve(String raw, UUID contentId) {
        if (raw == null) {
            return null;
        }
        if (raw.startsWith(LOCAL)) {
            return contentId == null ? null : "/api/contents/" + contentId + "/cover";
        }
        return raw;
    }
}
