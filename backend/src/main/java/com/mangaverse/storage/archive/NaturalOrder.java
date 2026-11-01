package com.mangaverse.storage.archive;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.Locale;

/** Ordenação natural ("page2" antes de "page10"). */
public final class NaturalOrder implements Comparator<String> {

    public static final NaturalOrder INSTANCE = new NaturalOrder();

    @Override
    public int compare(String a, String b) {
        String x = a.toLowerCase(Locale.ROOT);
        String y = b.toLowerCase(Locale.ROOT);
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i);
            char cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int si = i;
                int sj = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) {
                    i++;
                }
                while (j < y.length() && Character.isDigit(y.charAt(j))) {
                    j++;
                }
                int cmp = new BigInteger(x.substring(si, i)).compareTo(new BigInteger(y.substring(sj, j)));
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                if (cx != cy) {
                    return Character.compare(cx, cy);
                }
                i++;
                j++;
            }
        }
        return Integer.compare(x.length() - i, y.length() - j);
    }
}
