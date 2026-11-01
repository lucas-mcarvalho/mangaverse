package com.mangaverse.config;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import com.mangaverse.provider.spi.ProviderException;

import io.netty.channel.ChannelOption;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.util.retry.Retry;

/** Fábrica de WebClients com timeouts, User-Agent e política de retry com backoff exponencial. */
@Component
public class HttpSupport {

    private static final int MAX_BUFFER = 32 * 1024 * 1024;

    private final AppProperties.Http http;
    private final WebClient.Builder builder;

    public HttpSupport(AppProperties properties, WebClient.Builder builder) {
        this.http = properties.http();
        this.builder = builder;
    }

    public WebClient client(String baseUrl) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) http.connectTimeout().toMillis())
                .responseTimeout(http.responseTimeout())
                .followRedirect(true);
        WebClient.Builder b = builder.clone()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("User-Agent", http.userAgent())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_BUFFER));
        if (baseUrl != null) {
            b.baseUrl(baseUrl);
        }
        return b.build();
    }

    public String userAgent() {
        return http.userAgent();
    }

    /** Executa a chamada de forma bloqueante, com retry para erros transitórios (5xx, 429, rede). */
    public <T> T execute(String providerCode, Mono<T> call) {
        return execute(providerCode, call, http.maxRetries());
    }

    public <T> T execute(String providerCode, Mono<T> call, int maxRetries) {
        try {
            return call
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(500))
                            .maxBackoff(Duration.ofSeconds(8))
                            .filter(HttpSupport::isTransient)
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()))
                    .block(http.responseTimeout().multipliedBy(maxRetries + 2L));
        } catch (WebClientResponseException e) {
            throw new ProviderException(providerCode, describeStatus(e.getStatusCode()), e);
        } catch (WebClientRequestException e) {
            throw new ProviderException(providerCode, "Fonte indisponível: " + rootMessage(e), e);
        } catch (ProviderException e) {
            throw e;
        } catch (RuntimeException e) {
            if (e.getCause() instanceof TimeoutException || e instanceof IllegalStateException && String.valueOf(e.getMessage()).contains("Timeout")) {
                throw new ProviderException(providerCode, "Tempo limite excedido ao consultar a fonte.", e);
            }
            throw new ProviderException(providerCode, "Erro ao consultar a fonte: " + rootMessage(e), e);
        }
    }

    static boolean isTransient(Throwable t) {
        if (t instanceof WebClientResponseException r) {
            int code = r.getStatusCode().value();
            return code == 429 || code >= 500;
        }
        return t instanceof WebClientRequestException || t instanceof TimeoutException || t instanceof IOException;
    }

    private static String describeStatus(HttpStatusCode status) {
        int code = status.value();
        if (code == 401 || code == 403) {
            return "Acesso negado pela fonte (HTTP " + code + "). Verifique as credenciais configuradas.";
        }
        if (code == 404) {
            return "Recurso não encontrado na fonte (HTTP 404).";
        }
        if (code == 429) {
            return "Limite de requisições da fonte atingido (HTTP 429). Tente novamente mais tarde.";
        }
        return "A fonte respondeu com erro HTTP " + code + ".";
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }
}
