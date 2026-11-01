package com.mangaverse.storage.archive;

import java.util.Locale;
import java.util.Set;

/** Detecção de imagens por assinatura (magic bytes) e extensão. */
public final class ImageTypes {

    public static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp");

    private ImageTypes() {
    }

    public static boolean hasImageExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    /** Retorna o MIME type detectado ou {@code null} se os bytes não forem uma imagem reconhecida. */
    public static String detect(byte[] d) {
        if (d == null || d.length < 12) {
            return null;
        }
        if ((d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if ((d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return "image/png";
        }
        if (d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'E' && d[10] == 'B' && d[11] == 'P') {
            return "image/webp";
        }
        if (d[0] == 'G' && d[1] == 'I' && d[2] == 'F' && d[3] == '8') {
            return "image/gif";
        }
        if (d[4] == 'f' && d[5] == 't' && d[6] == 'y' && d[7] == 'p' && d[8] == 'a' && d[9] == 'v' && d[10] == 'i') {
            return "image/avif";
        }
        if (d[0] == 'B' && d[1] == 'M') {
            return "image/bmp";
        }
        return null;
    }

    public static String extensionFor(String mime) {
        return switch (mime == null ? "" : mime) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            case "image/avif" -> "avif";
            case "image/bmp" -> "bmp";
            default -> "jpg";
        };
    }
}
