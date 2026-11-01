package com.mangaverse.content;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContentRepository extends JpaRepository<Content, UUID> {

    Optional<Content> findByLocalPath(String localPath);

    List<Content> findByNormalizedTitle(String normalizedTitle);
}
