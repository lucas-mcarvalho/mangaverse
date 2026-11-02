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
    void forcedTypeOverridesGenreGuess() {
        MockHttp http = new MockHttp(req -> Reply.json("""
                {"data":{"fetchSourceManga":{"hasNextPage":false,"mangas":[
                  {"id":165,"title":"Batman","thumbnailUrl":"/api/v1/manga/165/thumbnail","genre":[]}]}}}"""));
        SuwayomiSourceProvider auto = new SuwayomiSourceProvider(client(http), "1", "HQ Now!", "pt-BR");
        SuwayomiSourceProvider comics = new SuwayomiSourceProvider(client(http), "1", "HQ Now!", "pt-BR", ContentType.COMIC);
        assertThat(auto.search(new SearchQuery("batman", Set.of(), Set.of(), 5)).get(0).type()).isEqualTo(ContentType.MANGA);
        assertThat(comics.search(new SearchQuery("batman", Set.of(), Set.of(ContentType.COMIC), 5)))
                .singleElement().satisfies(t -> assertThat(t.type()).isEqualTo(ContentType.COMIC));
    }

    @Test
    void missingThumbnailIsRetriedAfterLoadingDetails() throws Exception {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        java.util.concurrent.atomic.AtomicBoolean detailsLoaded = new java.util.concurrent.atomic.AtomicBoolean();
        MockHttp http = new MockHttp(req -> {
            if (req.url().getPath().equals("/api/graphql")) {
                detailsLoaded.set(true);
                return Reply.json("{\"data\":{\"fetchManga\":{\"manga\":{\"id\":165}}}}");
            }
            return detailsLoaded.get() ? new Reply(200, "image/png", png.toByteArray()) : Reply.status(400);
        });
        SuwayomiClient client = client(http);

        var image = client.fetchThumbnail("/api/v1/manga/165/thumbnail");

        assertThat(image.data()).isEqualTo(png.toByteArray());
        assertThat(http.requests).extracting(java.net.URI::getPath)
                .containsExactly("/api/v1/manga/165/thumbnail", "/api/graphql", "/api/v1/manga/165/thumbnail");
        // Outros recursos (páginas, ícones) não disparam a recarga
        detailsLoaded.set(false);
        assertThatThrownBy(() -> client.fetchThumbnail("/api/v1/extension/icon/x.png")).isInstanceOf(ProviderException.class);
    }

    @Test
    void errorMessagesAreSummarizedWithoutStackTraces() {
        String trace = "Exception while fetching data (/fetchSourceManga) : Timeout\n\njava.io.IOException: Timeout\n\tat x.y(Unknown Source)";
        assertThat(SuwayomiClient.friendlyError(trace)).isEqualTo("Timeout");
        assertThat(SuwayomiClient.friendlyError("Exception while fetching data (/fetchSourceManga) : Cloudflare bypass currently disabled\n\tat a"))
                .contains("Cloudflare", "desativada", "FLARESOLVERR_ENABLED").doesNotContain("\tat");
        assertThat(SuwayomiClient.friendlyError("CloudFlare challenge failed\n\tat a"))
                .contains("Byparr/FlareSolverr", "integração ativa").doesNotContain("desativada", "\tat");
        MockHttp http = new MockHttp(req -> Reply.json("{\"errors\":[{\"message\":\"" + trace.replace("\n", "\\n").replace("\t", "\\t") + "\"}],\"data\":null}"));
        assertThatThrownBy(() -> client(http).execute(SuwayomiClient.Q_SOURCES, Map.of()))
                .isInstanceOf(ProviderException.class).hasMessage("Erro do Suwayomi: Timeout");
    }

    @Test
    void binaryRejectsHtmlEvenWhenServerLabelsItAsAnImage() {
        MockHttp http = new MockHttp(req -> new Reply(200, "image/jpg",
                "<!DOCTYPE html><html><body>Unavailable</body></html>".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client(http).fetchBinary("/api/v1/manga/1/chapter/0/page/0"))
                .isInstanceOf(ProviderException.class).hasMessageContaining("em vez de uma imagem");
    }

    @Test
    void binaryUsesDetectedImageTypeInsteadOfUpstreamHeader() throws Exception {
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png);
        MockHttp http = new MockHttp(req -> new Reply(200, "image/jpg", png.toByteArray()));

        var image = client(http).fetchBinary("/api/v1/manga/1/chapter/0/page/0");

        assertThat(image.contentType()).isEqualTo("image/png");
        assertThat(image.data()).isEqualTo(png.toByteArray());
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
