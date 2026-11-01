package com.mangaverse.provider;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import com.mangaverse.config.AppProperties;
import com.mangaverse.config.HttpSupport;

import reactor.core.publisher.Mono;

/** WebClient com respostas simuladas: nenhum teste depende de sites externos. */
public final class MockHttp {

    public record Reply(int status, String contentType, byte[] body) {
        public static Reply json(String body) {
            return new Reply(200, "application/json", body.getBytes());
        }

        public static Reply status(int status) {
            return new Reply(status, "application/json", "{}".getBytes());
        }
    }

    public final List<URI> requests = new ArrayList<>();
    private final Function<ClientRequest, Reply> handler;

    public MockHttp(Function<ClientRequest, Reply> handler) {
        this.handler = handler;
    }

    public static HttpSupport support() {
        AppProperties props = new AppProperties(null, null,
                new AppProperties.Http("MangaVerse-Test", Duration.ofSeconds(2), Duration.ofSeconds(2), 0), null);
        return new HttpSupport(props, WebClient.builder());
    }

    public WebClient client(String baseUrl) {
        WebClient.Builder b = WebClient.builder().exchangeFunction(req -> {
            requests.add(req.url());
            Reply r = handler.apply(req);
            return Mono.just(ClientResponse.create(HttpStatus.valueOf(r.status()))
                    .header("Content-Type", r.contentType()).body(new String(r.body(), java.nio.charset.StandardCharsets.ISO_8859_1)).build());
        });
        if (baseUrl != null) {
            b.baseUrl(baseUrl);
        }
        return b.build();
    }
}
