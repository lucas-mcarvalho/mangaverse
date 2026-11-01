package com.mangaverse.content;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ChapterRepository extends JpaRepository<Chapter, UUID> {

    @Query("""
            select c from Chapter c left join fetch c.source s left join fetch s.provider
            where c.content.id = :contentId
            order by c.number asc nulls last, c.volume asc nulls last, c.createdAt asc""")
    List<Chapter> findByContentOrdered(UUID contentId);

    @Query("select c.externalId from Chapter c where c.source.id = :sourceId and c.externalId is not null")
    Set<String> findExternalIdsBySource(UUID sourceId);

    Optional<Chapter> findBySourceIdAndExternalId(UUID sourceId, String externalId);

    Optional<Chapter> findByLocalPath(String localPath);

    long countByContentId(UUID contentId);

    long countByLocalPathIsNotNull();

    @Query("select c.content.id, count(c) from Chapter c where c.content.id in :ids group by c.content.id")
    List<Object[]> countByContentIds(Collection<UUID> ids);

    @Query("select c.content.id, count(c) from Chapter c where c.content.id in :ids and c.localPath is not null group by c.content.id")
    List<Object[]> countDownloadedByContentIds(Collection<UUID> ids);

    @Query("select c from Chapter c join fetch c.content where c.id = :id")
    Optional<Chapter> findWithContent(UUID id);

    @Query("select c from Chapter c where c.localPath is not null")
    List<Chapter> findAllLocal();
}
