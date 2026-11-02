package com.mangaverse.translation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mangaverse.common.ApiException;
import com.mangaverse.config.AppProperties;
import com.mangaverse.reader.ReaderService;
import com.mangaverse.storage.archive.PageData;
import com.mangaverse.user.AppUser;

class TranslationServiceTest {
    @TempDir Path dir;
    ReaderService reader;
    TranslationClient client;
    TranslationService service;
    AppProperties properties;
    UUID id;
    AppUser user;
    byte[] png = new byte[] { (byte)137, 80, 78, 71, 13, 10, 26, 10 };

    @BeforeEach void setup() {
        id = UUID.randomUUID();
        reader = mock(ReaderService.class);
        client = mock(TranslationClient.class);
        user = mock(AppUser.class);
        var view = mock(ReaderService.ReaderChapterView.class);
        when(view.pageCount()).thenReturn(3);
        when(reader.open(eq(id), any())).thenReturn(view);
        when(reader.page(eq(id), anyInt())).thenReturn(new PageData(new byte[] {1, 2}, "image/jpeg"));
        when(client.translate(any())).thenReturn(png);
        properties = new AppProperties(null, new AppProperties.Storage(null, null, dir.toString(), null), null, null);
        service = new TranslationService(reader, client, properties);
    }

    @AfterEach void close() { service.shutdown(); }

    void waitFor(String state) {
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(service.status(id).status()).isEqualTo(state));
    }

    @Test void preparesEntireEditionPersistsItAndDoesNotTranslateAgain() {
        assertThat(service.start(id, user).status()).isEqualTo("QUEUED");
        waitFor("READY");
        assertThat(service.status(id).completedPages()).isEqualTo(3);
        assertThat(service.page(id, 2).data()).isEqualTo(png);
        service.start(id, user);
        verify(client, times(3)).translate(any());
        service.shutdown();
        service = new TranslationService(reader, client, properties);
        assertThat(service.status(id).status()).isEqualTo("READY");
        assertThat(service.page(id, 0).contentType()).isEqualTo("image/png");
        assertThatThrownBy(() -> service.page(id, -1)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.page(id, 3)).isInstanceOf(ApiException.class);
    }

    @Test void retryReusesCompletedPagesAndRetainsFailureMessage() {
        // First batch succeeds, final page fails.
        AtomicInteger calls = new AtomicInteger();
        when(client.translate(any())).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 3) throw ApiException.conflict("Tradutor indisponível");
            return png;
        });
        service.start(id, user);
        waitFor("FAILED");
        assertThat(service.status(id).completedPages()).isEqualTo(2);
        assertThat(service.status(id).error()).contains("indisponível");
        service.start(id, user);
        waitFor("READY");
        verify(client, times(4)).translate(any());
    }

    @Test void duplicateStartDoesNotCreateASecondJobAndCancelDoesNotPublishPages() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.translate(any())).thenAnswer(inv -> {
            entered.countDown();
            release.await(3, TimeUnit.SECONDS);
            return png;
        });
        service.start(id, user);
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
        service.start(id, user);
        assertThat(service.cancel(id).status()).isEqualTo("CANCELLED");
        release.countDown();
        assertThatThrownBy(() -> service.page(id, 0)).isInstanceOf(ApiException.class);
        verify(client, atMost(2)).translate(any());
        assertThat(service.status(id).completedPages()).isZero();
    }

    @Test void restartResumesInterruptedJobWithoutRepeatingSavedPages() throws Exception {
        Path saved = dir.resolve("translations").resolve(id.toString()).resolve("en-ptbr-v2");
        Files.createDirectories(saved);
        Files.write(saved.resolve("00000.png"), png);
        Files.writeString(saved.resolve("job.properties"), "status=PROCESSING\ntotal=3\ncompleted=1\nupdated=2026-11-02T00:00:00Z\n");
        service.resume();
        waitFor("READY");
        verify(client, times(2)).translate(any());
        verify(reader, never()).page(id, 0);
    }

    @Test void redoDiscardsAReadyEditionAndTranslatesEveryPageAgain() throws Exception {
        service.start(id, user);
        waitFor("READY");
        service.redo(id, user);
        waitFor("READY");
        verify(client, times(6)).translate(any());
    }
}
