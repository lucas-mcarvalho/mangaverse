package com.mangaverse.provider.suwayomi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
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

class SuwayomiProviderTest {

    static final AppProperties.Suwayomi CONFIG = new AppProperties.Suwayomi(true, "http://suwayomi.test", null, null, null, 100, 8);

    private SuwayomiClient client(MockHttp http) {
        return new SuwayomiClient(CONFIG, MockHttp.support(), http.client(CONFIG.baseUrl()));
    }

    @Test
    void searchMapsMangasAndRelativeThumbnails() {
        MockHttp http = new MockHttp(req -> Reply.json("""
                {"data":{"fetchSourceManga":{"hasNextPage":false,"mangas":[
                  {"id":3,"title":"The Berserker's Second Playthrough","thumbnailUrl":"/api/v1/manga/3/thumbnail","status":"ONGOING",
                   "genre":["Ação","Coreano","Webtoon"],"realUrl":"https://mangadex.org/title/x"}]}}}"""));
        SuwayomiSourceProvider provider = new SuwayomiSourceProvider(client(http), "2655149515337070132", "MangaDex", "pt-BR");

        List<RemoteTitle> results = provider.search(new SearchQuery("berserk", Set.of(), Set.of(), 10));

        assertThat(results).singleElement().satisfies(t -> {
            assertThat(t.providerCode()).isEqualTo("suwayomi:2655149515337070132");
            assertThat(t.externalId()).isEqualTo("3");
            assertThat(t.coverUrl()).isEqualTo("suwayomi:/api/v1/manga/3/thumbnail");
            assertThat(t.type()).isEqualTo(ContentType.MANHWA);
            assertThat(t.languages()).containsExactly("pt-br");
            assertThat(t.status()).isEqualTo("ongoing");
        });
    }

    @Test
    void partialNoChaptersErrorIsTreatedAsEmptyList() {
        MockHttp http = new MockHttp(req -> Reply.json("""
                {"errors":[{"message":"No chapters found"}],"data":{"fetchMangaAndChapters":{"manga":{"id":1,"title":"X"},"chapters":null}}}"""));
        SuwayomiSourceProvider provider = new SuwayomiSourceProvider(client(http), "1", "Fonte", "pt-BR");
        assertThat(provider.getChapters("1", Set.of())).isEmpty();
        assertThat(provider.getDetails("1").title()).isEqualTo("X");
    }

    @Test
    void chaptersParseVolumeNumberAndDates() {
        MockHttp http = new MockHttp(req -> Reply.json("""
                {"data":{"fetchMangaAndChapters":{"manga":{"id":2,"title":"Y"},"chapters":[
                  {"id":10,"name":"Vol.2 Ch.15.5","chapterNumber":15.5,"scanlator":"Grupo","uploadDate":"1700751457000","pageCount":-1},
                  {"id":11,"name":"Extra","chapterNumber":-1.0,"scanlator":null,"uploadDate":"0","pageCount":12}]}}}"""));
        SuwayomiSourceProvider provider = new SuwayomiSourceProvider(client(http), "1", "Fonte", "pt-BR");

        List<RemoteChapter> chapters = provider.getChapters("2", Set.of());

        assertThat(chapters.get(0).volume()).isEqualTo("2");
        assertThat(chapters.get(0).number()).isEqualByComparingTo("15.5");
        assertThat(chapters.get(0).publishedAt()).isNotNull();
        assertThat(chapters.get(0).pageCount()).isNull();
        assertThat(chapters.get(1).number()).isNull();
        assertThat(chapters.get(1).publishedAt()).isNull();
        assertThat(chapters).allMatch(c -> c.downloadable() && "pt-br".equals(c.language()));
    }

    @Test
    void graphQlErrorsWithoutDataFail() {
        MockHttp http = new MockHttp(req -> Reply.json("{\"errors\":[{\"message\":\"Source not found\"}],\"data\":null}"));
        SuwayomiClient client = client(http);
        assertThatThrownBy(() -> client.execute(SuwayomiClient.Q_SOURCES, Map.of()))
                .isInstanceOf(ProviderException.class).hasMessageContaining("Source not found");
    }

    @Test
    void pagesAndBinaryPathsAreValidated() {
        MockHttp http = new MockHttp(req -> Reply.json("{\"data\":{\"fetchChapterPages\":{\"pages\":[\"/api/v1/manga/2/chapter/1/page/0\"]}}}"));
        SuwayomiClient client = client(http);
        SuwayomiSourceProvider provider = new SuwayomiSourceProvider(client, "1", "Fonte", "pt-BR");
        assertThat(provider.getPages("5")).extracting(PageRef::url).containsExactly("/api/v1/manga/2/chapter/1/page/0");
        assertThatThrownBy(() -> client.fetchBinary("/api/v1/../../etc/passwd")).isInstanceOf(ProviderException.class);
        assertThatThrownBy(() -> client.fetchBinary("http://evil/api/v1/x")).isInstanceOf(ProviderException.class);
        assertThat(SuwayomiSourceProvider.handles("suwayomi:123")).isTrue();
        assertThat(SuwayomiSourceProvider.handles("suwayomi:abc")).isFalse();
    }

    @Test
    void disabledIntegrationDoesNotCallServer() {
        MockHttp http = new MockHttp(req -> Reply.json("{}"));
        SuwayomiClient client = new SuwayomiClient(new AppProperties.Suwayomi(false, "http://x", null, null, null, 1, 1),
                MockHttp.support(), http.client("http://x"));
        assertThatThrownBy(() -> client.execute(SuwayomiClient.Q_ABOUT, Map.of())).isInstanceOf(ProviderException.class);
        assertThat(http.requests).isEmpty();
    }
}
