package com.mangaverse.provider;

import java.net.URI;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.mangaverse.common.ApiException;
import com.mangaverse.config.HttpSupport;
import com.mangaverse.provider.spi.FetchedImage;
import com.mangaverse.provider.suwayomi.SuwayomiClient;
import com.mangaverse.provider.suwayomi.SuwayomiSourceProvider;
import com.mangaverse.storage.archive.ImageTypes;

/**
 * Proxy de capas/ícones. Só aceita hosts conhecidos (evita SSRF) e referências internas do Suwayomi.
 */
@RestController
@RequestMapping("/api/images")
public class ImageProxyController {

    private static final Set<String> ALLOWED_HOSTS = Set.of("uploads.mangadex.org", "comicvine.gamespot.com",
            "raw.githubusercontent.com");

    private final HttpSupport http;
    private final SuwayomiClient suwayomi;
    private final WebClient client;
    private final Cache<String, FetchedImage> cache = Caffeine.newBuilder()
            .maximumWeight(150L * 1024 * 1024).weigher((String k, FetchedImage v) -> v.data().length)
            .expireAfterWrite(Duration.ofHours(12)).build();

    public ImageProxyController(HttpSupport http, SuwayomiClient suwayomi) {
        this.http = http;
        this.suwayomi = suwayomi;
        this.client = http.client(null);
    }

    @GetMapping
    public ResponseEntity<byte[]> image(@RequestParam("src") String src) {
        if (src == null || src.length() > 2048) {
            throw ApiException.badRequest("Imagem inválida.");
        }
        FetchedImage image = cache.get(src, this::load);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType()))
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate())
                .header("X-Content-Type-Options", "nosniff")
                .body(image.data());
    }

    private FetchedImage load(String src) {
        FetchedImage image;
        if (src.startsWith(SuwayomiSourceProvider.COVER_PREFIX)) {
            image = suwayomi.fetchBinary(src.substring(SuwayomiSourceProvider.COVER_PREFIX.length()));
        } else {
            URI uri;
            try {
                uri = URI.create(src);
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("URL de imagem inválida.");
            }
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase())) {
                throw ApiException.forbidden("Host de imagem não permitido.");
            }
            var response = http.execute("images", client.get().uri(uri).retrieve().toEntity(byte[].class), 1);
            byte[] body = response.getBody() == null ? new byte[0] : response.getBody();
            image = new FetchedImage(body, "image/jpeg");
        }
        String detected = ImageTypes.detect(image.data());
        if (detected == null) {
            // SVG e outros formatos ativos não são servidos para evitar XSS na mesma origem
            throw ApiException.badRequest("O recurso não é uma imagem suportada.");
        }
        return new FetchedImage(image.data(), detected);
    }
}
