package com.mangaverse.integration;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

import com.mangaverse.provider.spi.ContentProvider;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.FetchedImage;
import com.mangaverse.provider.spi.PageRef;
import com.mangaverse.provider.spi.ProviderCapabilities;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.provider.spi.RemoteTitleDetails;
import com.mangaverse.provider.spi.SearchQuery;

/** Provedor em memória para testes: capítulos configuráveis e páginas PNG geradas localmente. */
public class FakeProvider implements ContentProvider {

    private final String code;
    public final List<RemoteChapter> chapters = new ArrayList<>();
    public int pagesPerChapter = 3;
    public volatile boolean failPages;
    public final AtomicInteger pageFetches = new AtomicInteger();
    public String searchId = "t1";
    public String searchTitle = "Título Falso";

    public FakeProvider(String code) {
        this.code = code;
    }

    public static RemoteChapter chapter(String id, String number) {
        return new RemoteChapter(id, new BigDecimal(number), number, "1", "Cap " + number, "pt-br", "Grupo", null, null, true, true, null);
    }

    public static RemoteChapter officialOnly(String id, String number) {
        return new RemoteChapter(id, new BigDecimal(number), number, "1", "Cap " + number, "pt-br", null, null, 0, false, false,
                "https://mangaplus.shueisha.co.jp/viewer/" + id);
    }

    @Override public String code() { return code; }
    @Override public String displayName() { return "Fake"; }
    @Override public ProviderCapabilities capabilities() { return new ProviderCapabilities(true, true, true, true, false, true); }

    @Override
    public List<RemoteTitle> search(SearchQuery query) {
        return List.of(new RemoteTitle(code, "Fake " + code, searchId, searchTitle, List.of(), null, null, ContentType.MANGA, Set.of("pt-br"), null, null, null, null));
    }

    @Override
    public RemoteTitleDetails getDetails(String externalId) {
        return new RemoteTitleDetails(code, externalId, "Título " + externalId, List.of(), "Sinopse", null, ContentType.MANGA,
                List.of("Autor"), List.of(), null, List.of("Ação"), List.of(), Set.of("pt-br"), "ja", "ongoing", 2020, null);
    }

    @Override
    public List<RemoteChapter> getChapters(String externalId, Set<String> languages) {
        return List.copyOf(chapters);
    }

    @Override
    public Set<String> getAvailableLanguages(String externalId) {
        return Set.of("pt-br");
    }

    @Override
    public List<PageRef> getPages(String chapterExternalId) {
        List<PageRef> pages = new ArrayList<>();
        for (int i = 0; i < pagesPerChapter; i++) {
            pages.add(new PageRef(i, "fake://" + chapterExternalId + "/" + i));
        }
        return pages;
    }

    @Override
    public FetchedImage fetchPage(PageRef page) {
        pageFetches.incrementAndGet();
        if (failPages) {
            throw new ProviderException(code, "Fonte indisponível (simulado)");
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(10 + page.index(), 10, BufferedImage.TYPE_INT_RGB), "png", out);
            return new FetchedImage(out.toByteArray(), "image/png");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ProviderHealth health() {
        return ProviderHealth.online("ok");
    }
}
