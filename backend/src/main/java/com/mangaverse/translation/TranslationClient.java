package com.mangaverse.translation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.mangaverse.common.ApiException;
import com.mangaverse.settings.SettingsService;
import com.mangaverse.storage.archive.PageData;

/** The worker is internal: page bytes never leave the configured local service. */
@Component
public class TranslationClient {
    private final String baseUrl;
    private final SettingsService settings;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public TranslationClient(@Value("${mangaverse.translation.url:http://localhost:8091}") String baseUrl,
                             SettingsService settings) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.settings = settings;
    }

    public byte[] translate(PageData page) {
        if (page.data().length > 25 * 1024 * 1024) {
            throw ApiException.conflict("Página excede o limite de 25 MB para tradução.");
        }
        try {
            var request = HttpRequest.newBuilder(URI.create(baseUrl + "/translate"))
                    .timeout(Duration.ofMinutes(10))
                    .header("Content-Type", page.contentType())
                    .header("X-Threads", Integer.toString(settings.translationThreads()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(page.data())).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw ApiException.conflict("O tradutor não conseguiu processar a página (HTTP " + response.statusCode()
                        + "). Confira os logs do serviço translator; tente novamente para retomar as páginas restantes.");
            }
            byte[] png = response.body();
            if (png.length < 8 || png[0] != (byte) 137 || png[1] != 80 || png[2] != 78 || png[3] != 71) {
                throw ApiException.conflict("O tradutor retornou uma imagem inválida.");
            }
            return png;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiException.conflict("Tradução interrompida. As páginas prontas foram preservadas.");
        } catch (java.io.IOException e) {
            throw ApiException.conflict("Tradutor local indisponível. Inicie o serviço translator e tente novamente.");
        }
    }
}
