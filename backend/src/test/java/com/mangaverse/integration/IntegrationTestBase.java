package com.mangaverse.integration;

import static org.mockito.Mockito.lenient;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.mangaverse.content.Chapter;
import com.mangaverse.content.ChapterRepository;
import com.mangaverse.content.Content;
import com.mangaverse.content.ContentRepository;
import com.mangaverse.content.ContentSource;
import com.mangaverse.content.ContentSourceRepository;
import com.mangaverse.provider.ProviderRegistry;
import com.mangaverse.provider.ProviderRepository;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.user.AppUser;
import com.mangaverse.user.AppUserRepository;

/**
 * Base dos testes de integração: PostgreSQL real (Testcontainers) com as migrations Flyway,
 * agendamento desligado e provedores substituídos por um provedor falso (sem acesso à internet).
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final Path STORAGE;

    static {
        POSTGRES.start();
        try {
            STORAGE = Files.createTempDirectory("mangaverse-it");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("mangaverse.storage.allowed-roots", () -> STORAGE.toString());
        r.add("mangaverse.storage.library-dir", () -> STORAGE.resolve("Biblioteca").toString());
        r.add("mangaverse.storage.data-dir", () -> STORAGE.resolve(".data").toString());
    }

    @MockitoBean
    protected ProviderRegistry registry;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ContentRepository contents;
    @Autowired protected ContentSourceRepository sources;
    @Autowired protected ChapterRepository chapters;
    @Autowired protected ProviderRepository providers;
    @Autowired protected AppUserRepository users;
    @Autowired protected CacheManager cacheManager;

    protected FakeProvider fake;

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE update_history, downloads, reading_progress, library_entries, categories, chapters, content_sources, contents, app_settings CASCADE");
        cacheManager.getCacheNames().forEach(n -> cacheManager.getCache(n).clear());
        fake = new FakeProvider("mangadex");
        lenient().when(registry.require("mangadex")).thenReturn(fake);
        lenient().when(registry.find("mangadex")).thenReturn(Optional.of(fake));
    }

    protected AppUser admin() {
        return users.findByUsername("admin").orElseThrow();
    }

    protected Content newContent(String title, ContentType type) {
        return contents.save(new Content(title, type));
    }

    protected ContentSource newSource(Content content, String externalId) {
        return sources.save(new ContentSource(content, providers.findByCode("mangadex").orElseThrow(), externalId));
    }

    protected Chapter remoteChapter(Content content, ContentSource source, String externalId, String number) {
        Chapter c = new Chapter(content, source, externalId);
        c.setNumber(new BigDecimal(number));
        c.setLanguage("pt-br");
        c.setDownloadable(true);
        c.setReadable(true);
        return chapters.save(c);
    }
}
