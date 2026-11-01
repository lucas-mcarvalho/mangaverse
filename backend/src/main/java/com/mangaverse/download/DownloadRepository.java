package com.mangaverse.download;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DownloadRepository extends JpaRepository<Download, UUID> {

    @Query("""
            select d from Download d join fetch d.chapter c join fetch c.content
            where d.status in :statuses order by d.createdAt asc""")
    List<Download> findByStatuses(Collection<DownloadStatus> statuses);

    @Query("""
            select d from Download d join fetch d.chapter c join fetch c.content
            order by case when d.status in ('DOWNLOADING','QUEUED','PAUSED') then 0 else 1 end, d.createdAt desc""")
    List<Download> findForListing(Pageable pageable);

    @Query("select d from Download d where d.status = 'QUEUED' order by d.createdAt asc")
    List<Download> findQueued(Pageable pageable);

    boolean existsByChapterIdAndStatusIn(UUID chapterId, Collection<DownloadStatus> statuses);

    Optional<Download> findFirstByChapterIdAndStatusIn(UUID chapterId, Collection<DownloadStatus> statuses);

    @Query("select d.chapter.id, d.status from Download d where d.chapter.content.id = :contentId and d.status in ('QUEUED','DOWNLOADING','PAUSED','FAILED')")
    List<Object[]> findStatusesForContent(UUID contentId);

    @Query("select d.chapter.content.id, count(d) from Download d where d.chapter.content.id in :ids and d.status in ('QUEUED','DOWNLOADING','PAUSED') group by d.chapter.content.id")
    List<Object[]> countActiveByContentIds(Collection<UUID> ids);

    @Modifying
    @Query("update Download d set d.status = 'QUEUED', d.speedBps = 0 where d.status = 'DOWNLOADING'")
    int requeueInterrupted();

    @Modifying
    @Query("delete from Download d where d.status in ('COMPLETED','CANCELLED')")
    int deleteFinished();

    long countByStatus(DownloadStatus status);
}
