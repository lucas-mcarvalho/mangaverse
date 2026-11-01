package com.mangaverse.content;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ContentSourceRepository extends JpaRepository<ContentSource, UUID> {

    @Query("select cs from ContentSource cs where cs.provider.code = :code and cs.externalId = :externalId")
    Optional<ContentSource> findByProviderCodeAndExternalId(String code, String externalId);

    List<ContentSource> findByContentId(UUID contentId);

    boolean existsByProviderId(UUID providerId);

    @Query("select cs from ContentSource cs join fetch cs.content where cs.provider.id = :providerId")
    List<ContentSource> findByProviderId(UUID providerId);
}
