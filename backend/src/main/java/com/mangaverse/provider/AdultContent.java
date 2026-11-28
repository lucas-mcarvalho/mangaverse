package com.mangaverse.provider;

import java.util.regex.Pattern;

/**
 * Identifica fontes/extensões só de conteúdo adulto (hentai etc.), que ficam separadas do resto e só aparecem com o
 * modo +18 ligado. Vale o aviso "NSFW" do repositório (no formato novo, fontes comuns com algum título adulto vêm
 * como "MIXED") ou o nome da extensão.
 */
public final class AdultContent {

    private static final Pattern ADULT_NAME = Pattern.compile(
            "hentai|sexo|\\bsex|porn|xxx|nsfw|erotic|er[oó]tico|\\becchi|doujin|lewd|\\br-?18|\\+18|18\\+|adult|nhentai|hitomi"
                    + "|hipercool|tsumino|pururin|luscious|imhentai|asmhentai|multporn|8muses|simply ?hentai",
            Pattern.CASE_INSENSITIVE);

    private AdultContent() {
    }

    public static boolean isAdultName(String name) {
        return name != null && ADULT_NAME.matcher(name).find();
    }

    /** @param pkgName pacote da extensão (só a última parte é considerada) */
    public static boolean isAdult(String name, String pkgName, String contentWarning) {
        String shortPkg = pkgName == null ? null : pkgName.substring(pkgName.lastIndexOf('.') + 1);
        return "NSFW".equalsIgnoreCase(contentWarning) || isAdultName(name) || isAdultName(shortPkg);
    }
}
