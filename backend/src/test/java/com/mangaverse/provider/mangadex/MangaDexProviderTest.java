package com.mangaverse.provider.mangadex;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mangaverse.config.AppProperties;
import com.mangaverse.provider.MockHttp;
import com.mangaverse.provider.MockHttp.Reply;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.PageRef;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.provider.spi.SearchQuery;

class MangaDexProviderTest {

    static final String MANGA_ID = "801513ba-a712-498c-8f57-cae55b38cc92";
    static final AppProperties.MangaDex CONFIG = new AppProperties.MangaDex(true, "https://api.test", "https://uploads.test", "", 100);

    static final String SEARCH = """
            {"result":"ok","data":[{"id":"%s","type":"manga","attributes":{
              "title":{"ja-ro":"Berserk"},"altTitles":[{"pt-br":"Berserk BR"},{"en":"Berserk"}],
              "description":{"en":"Guts...","pt-br":"Guts, o Espadachim Negro"},"originalLanguage":"ja","status":"ongoing","year":1989,
              "availableTranslatedLanguages":["en","pt-br"],"tags":[{"attributes":{"name":{"en":"Action"}}}]},
              "relationships":[{"type":"cover_art","attributes":{"fileName":"capa.jpg"}},{"type":"author","attributes":{"name":"Kentaro Miura"}}]}],
             "limit":20,"offset":0,"total":1}""".formatted(MANGA_ID);

    static String feed(int offset, int total, String... chapters) {
        return "{\"result\":\"ok\",\"data\":[" + String.join(",", chapters) + "],\"limit\":500,\"offset\":" + offset + ",\"total\":" + total + "}";
    }

    static String chapter(String id, String number, String externalUrl) {
        return """
                {"id":"%s","attributes":{"volume":"1","chapter":%s,"title":"T","translatedLanguage":"pt-br","externalUrl":%s,
                "isUnavailable":false,"publishAt":"2022-05-30T22:13:18+00:00","pages":%d},
                "relationships":[{"type":"scanlation_group","attributes":{"name":"Kousen Scans"}}]}"""
                .formatted(id, number == null ? "null" : "\"" + number + "\"", externalUrl == null ? "null" : "\"" + externalUrl + "\"", externalUrl == null ? 20 : 0);
    }

    @Test
    void searchParsesTitlesCoversLanguagesAndType() {
        MockHttp http = new MockHttp(req -> Reply.json(SEARCH));
        MangaDexProvider provider = new MangaDexProvider(CONFIG, MockHttp.support(), http.client(CONFIG.baseUrl()), http.client(null));

        List<RemoteTitle> results = provider.search(new SearchQuery("berserk", Set.of("pt-br"), Set.of(ContentType.MANGA), 10));

        assertThat(results).hasSize(1);
        RemoteTitle t = results.get(0);
        assertThat(t.title()).isEqualTo("Berserk");
        assertThat(t.altTitles()).startsWith("Berserk BR");
        assertThat(t.description()).isEqualTo("Guts, o Espadachim Negro"); // pt-br preferido
        assertThat(t.coverUrl()).isEqualTo("https://uploads.test/covers/" + MANGA_ID + "/capa.jpg.512.jpg");
        assertThat(t.languages()).containsExactlyInAnyOrder("en", "pt-br");
        assertThat(t.type()).isEqualTo(ContentType.MANGA);
        String query = http.requests.get(0).getRawQuery();
        assertThat(query).contains("title=berserk").contains("originalLanguage").contains("contentRating");
    }

    @Test
    void chaptersArePaginatedAndExternalChaptersAreNotDownloadable() {
        MockHttp http = new MockHttp(req -> {
            String q = req.url().getRawQuery();
            if (q.contains("offset=0")) {
                return Reply.json(feed(0, 501, chapter("c1", "1", null), chapter("c2", null, "https://oficial.example/cap2")));
            }
            return Reply.json(feed(500, 501, chapter("c3", "3.5", null)));
        });
        MangaDexProvider provider = new MangaDexProvider(CONFIG, MockHttp.support(), http.client(CONFIG.baseUrl()), http.client(null));

        List<RemoteChapter> chapters = provider.getChapters(MANGA_ID, Set.of("pt-br"));

        assertThat(chapters).extracting(RemoteChapter::externalId).containsExactly("c1", "c2", "c3");
        assertThat(chapters.get(0).downloadable()).isTrue();
        assertThat(chapters.get(0).scanlator()).isEqualTo("Kousen Scans");
        assertThat(chapters.get(1).readable()).isFalse();
        assertThat(chapters.get(1).downloadable()).isFalse();
        assertThat(chapters.get(1).externalUrl()).isEqualTo("https://oficial.example/cap2");
        assertThat(chapters.get(2).number()).isEqualByComparingTo("3.5");
        assertThat(http.requests).hasSize(2);
        assertThat(http.requests.get(0).getRawQuery()).contains("translatedLanguage").contains("pt-br");
    }

    @Test
    void pagesUseAtHomeServer() {
        MockHttp http = new MockHttp(req -> Reply.json("""
                {"result":"ok","baseUrl":"https://xyz.mangadex.network","chapter":{"hash":"h1","data":["1.jpg","2.jpg"]}}"""));
        MangaDexProvider provider = new MangaDexProvider(CONFIG, MockHttp.support(), http.client(CONFIG.baseUrl()), http.client(null));

        List<PageRef> pages = provider.getPages("bbea0894-09ab-4983-901d-368371a66708");

        assertThat(pages).extracting(PageRef::url).containsExactly(
                "https://xyz.mangadex.network/data/h1/1.jpg", "https://xyz.mangadex.network/data/h1/2.jpg");
    }

    @Test
    void rateLimitAndServerErrorsBecomeProviderExceptions() {
        MockHttp http = new MockHttp(req -> Reply.status(429));
        MangaDexProvider provider = new MangaDexProvider(CONFIG, MockHttp.support(), http.client(CONFIG.baseUrl()), http.client(null));
        assertThatThrownBy(() -> provider.search(new SearchQuery("x", Set.of(), Set.of(), 5)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("429");
        assertThat(provider.health().status().name()).isEqualTo("OFFLINE");
    }

    @Test
    void rejectsInvalidIdsAndForeignImageHosts() {
        MockHttp http = new MockHttp(req -> Reply.json("{}"));
        MangaDexProvider provider = new MangaDexProvider(CONFIG, MockHttp.support(), http.client(CONFIG.baseUrl()), http.client(null));
        assertThatThrownBy(() -> provider.getDetails("../../etc")).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> provider.fetchPage(new PageRef(0, "https://evil.example/a.jpg")))
                .isInstanceOf(ProviderException.class).hasMessageContaining("não permitido");
        assertThat(http.requests).isEmpty();
    }
}
