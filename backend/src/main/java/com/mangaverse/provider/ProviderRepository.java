package com.mangaverse.provider;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProviderRepository extends JpaRepository<Provider, UUID> {

    Optional<Provider> findByCode(String code);

    List<Provider> findByTypeOrderByPriorityAscNameAsc(ProviderType type);

    List<Provider> findAllByOrderByTypeAscPriorityAscNameAsc();

    List<Provider> findByEnabledTrueAndSupportsSearchTrueOrderByPriorityAsc();
}
