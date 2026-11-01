package com.mangaverse.reader;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ReadingProgressRepository extends JpaRepository<ReadingProgress, UUID> {

    Optional<ReadingProgress> findByUserIdAndChapterId(UUID userId, UUID chapterId);

    List<ReadingProgress> findByUserIdAndContentId(UUID userId, UUID contentId);

    @Query("""
            select p from ReadingProgress p join fetch p.chapter c join fetch p.content
            where p.user.id = :userId order by p.updatedAt desc""")
    List<ReadingProgress> findRecent(UUID userId, Pageable pageable);

    @Query("""
            select p.content.id, count(p) from ReadingProgress p
            where p.user.id = :userId and p.completed = true and p.content.id in :ids group by p.content.id""")
    List<Object[]> countCompletedByContent(UUID userId, Collection<UUID> ids);

    @Query("""
            select p from ReadingProgress p join fetch p.chapter
            where p.user.id = :userId and p.content.id in :ids order by p.updatedAt desc""")
    List<ReadingProgress> findLatestForContents(UUID userId, Collection<UUID> ids);
}
