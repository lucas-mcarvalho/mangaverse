package com.mangaverse.monitor;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface UpdateHistoryRepository extends JpaRepository<UpdateHistory, UUID> {

    @Query("select h from UpdateHistory h join fetch h.content left join fetch h.chapter order by h.createdAt desc")
    List<UpdateHistory> findRecent(Pageable pageable);

    @Query("select h from UpdateHistory h join fetch h.content left join fetch h.chapter where h.eventType = :type order by h.createdAt desc")
    List<UpdateHistory> findRecentByType(UpdateHistory.EventType type, Pageable pageable);

    long countBySeenFalse();

    @Modifying
    @Query("update UpdateHistory h set h.seen = true where h.seen = false")
    int markAllSeen();
}
