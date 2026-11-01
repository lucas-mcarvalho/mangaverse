package com.mangaverse.library;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LibraryEntryRepository extends JpaRepository<LibraryEntry, UUID> {

    @Query("select e from LibraryEntry e join fetch e.content left join fetch e.category where e.user.id = :userId order by e.addedAt desc")
    List<LibraryEntry> findAllForUser(UUID userId);

    Optional<LibraryEntry> findByUserIdAndContentId(UUID userId, UUID contentId);

    @Query("select e from LibraryEntry e join fetch e.content join fetch e.user where e.monitored = true")
    List<LibraryEntry> findMonitored();

    @Query("select e from LibraryEntry e join fetch e.content where e.id = :id and e.user.id = :userId")
    Optional<LibraryEntry> findForUser(UUID id, UUID userId);

    @Query("select count(e) > 0 from LibraryEntry e where e.content.id = :contentId and e.autoDownload = true")
    boolean anyAutoDownload(UUID contentId);
}
