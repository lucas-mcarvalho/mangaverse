package com.mangaverse.provider.comicvine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mangaverse.config.AppProperties;
import com.mangaverse.provider.MockHttp;
import com.mangaverse.provider.MockHttp.Reply;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.provider.spi.ProviderException;
import com.mangaverse.provider.spi.ProviderHealth;
import com.mangaverse.provider.spi.RemoteChapter;
import com.mangaverse.provider.spi.RemoteTitle;
import com.mangaverse.provider.spi.RemoteTitleDetails;
import com.mangaverse.provider.spi.SearchQuery;

class ComicVineProviderTest {

    static AppProperties.ComicVine config(String key) {
        return new AppProperties.ComicVine(true, "https://cv.test/api", key, 100);
    }

    @Test
    void requiresApiKey() {
        MockHttp http = new MockHttp(req -> Reply.json("{}"));
        ComicVineProvider provider = new ComicVineProvider(config(""), MockHttp.support(), http.client("https://cv.test/api"));
        assertThat(provider.health().status()).isEqualTo(ProviderHealth.Status.NOT_CONFIGURED);
        assertThatThrownBy(() -> provider.search(new SearchQuery("batman", Set.of(), Set.of(), 5)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("COMICVINE_API_KEY");
        assertThat(http.requests).isEmpty();
    }

    @Test
    void invalidKeyIsReported() {
        MockHttp http = new MockHttp(req -> Reply.json("{\"error\":\"Invalid API Key\",\"status_code\":100,\"results\":[]}"));
        ComicVineProvider provider = new ComicVineProvider(config("k"), MockHttp.support(), http.client("https://cv.test/api"));
        assertThatThrownBy(() -> provider.search(new SearchQuery("batman", Set.of(), Set.of(), 5)))
                .isInstanceOf(ProviderException.class).hasMessageContaining("inválida");
    }

    @Test
    void parsesVolumesDetailsAndIssues() {
        MockHttp http = new MockHttp(req -> {
            String path = req.url().getPath();
            if (path.endsWith("/search/")) {
                return Reply.json("""
                        {"status_code":1,"results":[{"id":12345,"name":"Absolute Batman","start_year":"2024",
                         "publisher":{"name":"DC Comics"},"deck":"Um novo Batman.","image":{"medium_url":"https://comicvine.gamespot.com/a/x.jpg"},
                         "site_detail_url":"https://comicvine.gamespot.com/absolute-batman/4050-12345/"}]}""");
            }
            if (path.contains("/volume/4050-12345/")) {
                return Reply.json("""
                        {"status_code":1,"results":{"id":12345,"name":"Absolute Batman","start_year":"2024","publisher":{"name":"DC Comics"},
                         "description":"<p>Bruce <b>Wayne</b></p>","image":{"original_url":"https://comicvine.gamespot.com/a/o.jpg"},
                         "characters":[{"name":"Batman"},{"name":"Alfred"}],"people":[{"name":"Scott Snyder"}]}}""");
            }
            return Reply.json("""
                    {"status_code":1,"number_of_total_results":2,"results":[
                     {"id":1,"name":"Parte 1","issue_number":"1","cover_date":"2024-10-01"},
                     {"id":2,"name":null,"issue_number":"1A","store_date":"2024-10-09"}]}""");
        });
        ComicVineProvider provider = new ComicVineProvider(config("chave"), MockHttp.support(), http.client("https://cv.test/api"));

        List<RemoteTitle> results = provider.search(new SearchQuery("batman absolute", Set.of(), Set.of(), 10));
        assertThat(results).singleElement().satisfies(t -> {
            assertThat(t.title()).isEqualTo("Absolute Batman");
            assertThat(t.publisher()).isEqualTo("DC Comics");
            assertThat(t.year()).isEqualTo(2024);
            assertThat(t.type()).isEqualTo(ContentType.COMIC);
        });
        assertThat(http.requests.get(0).getRawQuery()).contains("api_key=chave").contains("format=json").contains("resources=volume");

        RemoteTitleDetails details = provider.getDetails("12345");
        assertThat(details.description()).isEqualTo("Bruce Wayne");
        assertThat(details.characters()).containsExactly("Batman", "Alfred");
        assertThat(details.authors()).containsExactly("Scott Snyder");

        List<RemoteChapter> issues = provider.getChapters("12345", Set.of());
        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).number()).isEqualByComparingTo("1");
        assertThat(issues.get(1).number()).isNull();
        assertThat(issues.get(1).numberLabel()).isEqualTo("1A");
        assertThat(issues).allMatch(i -> !i.readable() && !i.downloadable());
    }

    @Test
    void doesNotSearchWhenOnlyMangaTypesRequested() {
        MockHttp http = new MockHttp(req -> Reply.json("{}"));
        ComicVineProvider provider = new ComicVineProvider(config("k"), MockHttp.support(), http.client("https://cv.test/api"));
        assertThat(provider.search(new SearchQuery("x", Set.of(), Set.of(ContentType.MANGA), 5))).isEmpty();
        assertThat(http.requests).isEmpty();
    }
}
