package com.mangaverse.config;

import java.time.Duration;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/** Cache de metadados de provedores, com expiração por tipo de dado. */
@Configuration
public class CacheConfig {

    public static final String SEARCH = "search";
    public static final String DETAILS = "details";
    public static final String CHAPTERS = "chapters";
    public static final String DISCOVER = "discover";
    public static final String PAGES = "pages";

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);
        manager.registerCustomCache(SEARCH, Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofMinutes(10)).maximumSize(500).build());
        manager.registerCustomCache(DETAILS, Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofHours(1)).maximumSize(1000).build());
        manager.registerCustomCache(CHAPTERS, Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofMinutes(15)).maximumSize(500).build());
        manager.registerCustomCache(DISCOVER, Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofMinutes(30)).maximumSize(100).build());
        manager.registerCustomCache(PAGES, Caffeine.newBuilder().recordStats().expireAfterWrite(Duration.ofMinutes(8)).maximumSize(300).build());
        return manager;
    }
}
