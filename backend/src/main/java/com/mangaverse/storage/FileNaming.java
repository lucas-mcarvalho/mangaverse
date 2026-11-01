package com.mangaverse.storage;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Nomes de arquivos/pastas seguros para Linux e Windows. */
public final class FileNaming {

    private static final Pattern INVALID = Pattern.compile("[<>:\"/\\\\|?*\\p{Cntrl}]");
    private static final Set<String> WINDOWS_RESERVED = Set.of("CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private static final Pattern[] NUMBER_PATTERNS = {
            Pattern.compile("(?i)(?:cap(?:[ií]tulo)?|ch(?:apter)?|chap|c|edi[cç][aã]o|ed|issue|#|n[º°o]?\\.?)\\s*[._-]?\\s*(\\d+(?:[.,]\\d+)?)"),
            Pattern.compile("(\\d+(?:[.,]\\d+)?)(?!.*\\d)")
    };
    private static final Pattern VOLUME = Pattern.compile("(?i)(?:\\bvol(?:ume)?|\\bv|\\btomo|\\bt)\\s*[._-]?\\s*(\\d+)");

    private FileNaming() {
    }

    public static String sanitize(String name) {
        if (name == null) {
            return "_";
        }
        String n = Normalizer.normalize(name, Normalizer.Form.NFC);
        n = INVALID.matcher(n).replaceAll(" ").replaceAll("\\s+", " ").trim();
        n = n.replaceAll("^[. ]+|[. ]+$", "");
        if (n.isEmpty() || n.equals(".") || n.equals("..")) {
            n = "_";
        }
        if (WINDOWS_RESERVED.contains(n.toUpperCase(Locale.ROOT).split("\\.")[0])) {
            n = "_" + n;
        }
        if (n.length() > 120) {
            n = n.substring(0, 120).trim();
        }
        return n;
    }

    /** "Capitulo 001.cbz", "Capitulo 010.5.cbz", "Edicao 001.cbz". */
    public static String chapterFileName(boolean comic, BigDecimal number, String fallbackTitle, String fallbackId) {
        String prefix = comic ? "Edicao" : "Capitulo";
        if (number != null && number.signum() >= 0) {
            BigDecimal n = number.stripTrailingZeros();
            String integer = String.format(Locale.ROOT, "%03d", n.toBigInteger().intValue());
            BigDecimal fraction = n.subtract(new BigDecimal(n.toBigInteger()));
            String suffix = fraction.signum() == 0 ? "" : fraction.toPlainString().substring(1); // ".5"
            return prefix + " " + integer + suffix + ".cbz";
        }
        String label = fallbackTitle != null && !fallbackTitle.isBlank() ? fallbackTitle : fallbackId;
        return sanitize(prefix + " - " + label) + ".cbz";
    }

    public static BigDecimal parseNumber(String fileName) {
        String base = stripExtension(fileName);
        String withoutVolume = VOLUME.matcher(base).replaceAll(" ");
        for (Pattern p : NUMBER_PATTERNS) {
            Matcher m = p.matcher(withoutVolume);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1).replace(',', '.'));
                } catch (NumberFormatException ignored) {
                    // tenta o próximo padrão
                }
            }
        }
        return null;
    }

    public static String parseVolume(String fileName) {
        Matcher m = VOLUME.matcher(stripExtension(fileName));
        return m.find() ? String.valueOf(Integer.parseInt(m.group(1))) : null;
    }

    public static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 && fileName.length() - dot <= 5 ? fileName.substring(0, dot) : fileName;
    }

    public static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
