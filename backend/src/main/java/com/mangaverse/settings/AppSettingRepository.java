package com.mangaverse.settings;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, UUID> {

    Optional<AppSetting> findByKey(String key);
}
