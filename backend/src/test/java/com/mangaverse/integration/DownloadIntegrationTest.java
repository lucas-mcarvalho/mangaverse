package com.mangaverse.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.mangaverse.content.Chapter;
import com.mangaverse.content.Content;
import com.mangaverse.content.ContentSource;
import com.mangaverse.download.CbzWriter;
import com.mangaverse.download.Download;
import com.mangaverse.download.DownloadEngine;
import com.mangaverse.download.DownloadRepository;
import com.mangaverse.download.DownloadService;
import com.mangaverse.download.DownloadStatus;
import com.mangaverse.provider.spi.ContentType;
import com.mangaverse.storage.archive.CbzPageSource;

class DownloadIntegrationTest extends IntegrationTestBase {

    @Autowired DownloadService downloads;
    @Autowired DownloadEngine engine;
    @Autowired DownloadRepository repository;

    private Download waitFor(UUID chapterId, DownloadStatus... expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            engine.dispatch();
            var d = repository.findAll().stream().filter(x -> x.getChapter().getId().equals(chapterId)).findFirst();
            if (d.isPresent() && List.of(expected).contains(d.get().getStatus()) && !engine.isRunning(d.get().getId())) {
                return d.get();
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Download não atingiu " + List.of(expected));
    }

    @Test
    void downloadsChapterToCbzInLibraryLayoutAndAvoidsDuplicates() throws Exception {
        Content c = newContent("One Piece", ContentType.MANGA);
        ContentSource s = newSource(c, "op");
        Chapter ch = remoteChapter(c, s, "c1", "1");

        var result = downloads.enqueue(List.of(ch.getId(), ch.getId()), false);
        assertThat(result.queued()).isEqualTo(1);
        assertThat(result.skipped()).singleElement().satisfies(sk -> assertThat(sk.reason()).isEqualTo("Já está na fila"));

        Download done = waitFor(ch.getId(), DownloadStatus.COMPLETED, DownloadStatus.FAILED);
        assertThat(done.getStatus()).as(done.getErrorMessage()).isEqualTo(DownloadStatus.COMPLETED);
        Path file = Path.of(done.getFilePath());
        assertThat(file).exists();
        assertThat(file.toString()).endsWith("Biblioteca/Mangas/One Piece/Capitulo 001.cbz");
        assertThat(CbzWriter.verify(file)).isEqualTo(3);
        try (CbzPageSource source = new CbzPageSource(file)) {
            assertThat(source.pageNames()).hasSize(3);
        }
        Chapter updated = chapters.findById(ch.getId()).orElseThrow();
        assertThat(updated.getLocalPath()).isEqualTo(file.toString());
        assertThat(updated.getChecksum()).isEqualTo(CbzWriter.sha256(file));

        var again = downloads.enqueue(List.of(ch.getId()), false);
        assertThat(again.queued()).isZero();
        assertThat(again.skipped().get(0).reason()).isEqualTo("Já baixado");
        assertThat(downloads.verifyAll().issues()).isEmpty();
    }

    @Test
    void failingSourceMarksDownloadAsFailedAndRetryWorks() throws Exception {
        Content c = newContent("Falha", ContentType.MANGA);
        Chapter ch = remoteChapter(c, newSource(c, "f"), "c1", "1");
        fake.failPages = true;
        downloads.enqueue(List.of(ch.getId()), false);
        Download failed = waitFor(ch.getId(), DownloadStatus.FAILED, DownloadStatus.COMPLETED);
        assertThat(failed.getStatus()).isEqualTo(DownloadStatus.FAILED);
        assertThat(failed.getErrorMessage()).contains("Fonte indisponível");
        assertThat(chapters.findById(ch.getId()).orElseThrow().getLocalPath()).isNull();

        fake.failPages = false;
        downloads.retry(failed.getId());
        assertThat(waitFor(ch.getId(), DownloadStatus.COMPLETED, DownloadStatus.FAILED).getStatus()).isEqualTo(DownloadStatus.COMPLETED);
    }

    @Test
    void pauseResumeAndCancelQueuedDownloads() {
        Content c = newContent("Controle", ContentType.COMIC);
        ContentSource s = newSource(c, "ctl");
        Chapter a = remoteChapter(c, s, "a", "1");
        Chapter b = remoteChapter(c, s, "b", "2");
        downloads.enqueue(List.of(a.getId(), b.getId()), false);
        Download da = repository.findAll().stream().filter(d -> d.getChapter().getId().equals(a.getId())).findFirst().orElseThrow();
        Download db = repository.findAll().stream().filter(d -> d.getChapter().getId().equals(b.getId())).findFirst().orElseThrow();

        downloads.pause(da.getId());
        assertThat(repository.findById(da.getId()).orElseThrow().getStatus()).isEqualTo(DownloadStatus.PAUSED);
        downloads.resume(da.getId());
        assertThat(repository.findById(da.getId()).orElseThrow().getStatus()).isEqualTo(DownloadStatus.QUEUED);
        downloads.cancel(db.getId());
        assertThat(repository.findById(db.getId()).orElseThrow().getStatus()).isEqualTo(DownloadStatus.CANCELLED);
        // capítulo cancelado pode voltar à fila
        assertThat(downloads.enqueue(List.of(b.getId()), false).queued()).isEqualTo(1);
    }

    @Test
    void comicsAreSavedAsEditionsAndNonDownloadableChaptersAreSkipped() throws Exception {
        Content hq = newContent("Batman Absolute", ContentType.COMIC);
        ContentSource s = newSource(hq, "bat");
        Chapter issue = remoteChapter(hq, s, "i2", "2");
        Chapter external = remoteChapter(hq, s, "ext", "3");
        external.setDownloadable(false);
        external.setExternalUrl("https://oficial.example");
        chapters.save(external);

        var r = downloads.enqueue(List.of(issue.getId(), external.getId()), false);
        assertThat(r.queued()).isEqualTo(1);
        assertThat(r.skipped().get(0).reason()).isEqualTo("Disponível apenas no site oficial");
        Download done = waitFor(issue.getId(), DownloadStatus.COMPLETED, DownloadStatus.FAILED);
        assertThat(done.getFilePath()).endsWith("Biblioteca/HQs/Batman Absolute/Edicao 002.cbz");
    }

    @Test
    void existingValidFileIsAdoptedInsteadOfDownloadedAgain() throws Exception {
        Content c = newContent("Adotado", ContentType.MANGA);
        Chapter ch = remoteChapter(c, newSource(c, "ad"), "c5", "5");
        Path dir = Files.createDirectories(STORAGE.resolve("Biblioteca/Mangas/Adotado"));
        Path page = Files.write(dir.resolve("p.png"), fake.fetchPage(new com.mangaverse.provider.spi.PageRef(0, "x")).data());
        CbzWriter.write(dir.resolve("Capitulo 005.cbz"), List.of(page), null);
        int before = fake.pageFetches.get();

        downloads.enqueue(List.of(ch.getId()), false);
        Download done = waitFor(ch.getId(), DownloadStatus.COMPLETED, DownloadStatus.FAILED);

        assertThat(done.getStatus()).isEqualTo(DownloadStatus.COMPLETED);
        assertThat(fake.pageFetches.get()).isEqualTo(before);
    }
}
