package com.mangaverse.provider;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.mangaverse.config.AppProperties;
import com.mangaverse.config.HttpSupport;
import com.mangaverse.provider.comicvine.ComicVineProvider;
import com.mangaverse.provider.mangadex.MangaDexProvider;
import com.mangaverse.provider.suwayomi.SuwayomiClient;

@Configuration
public class ProviderConfig {

    @Bean
    MangaDexProvider mangaDexProvider(AppProperties props, HttpSupport http) {
        return new MangaDexProvider(props.providers().mangadex(), http);
    }

    @Bean
    ComicVineProvider comicVineProvider(AppProperties props, HttpSupport http) {
        return new ComicVineProvider(props.providers().comicvine(), http);
    }

    @Bean
    SuwayomiClient suwayomiClient(AppProperties props, HttpSupport http) {
        return new SuwayomiClient(props.providers().suwayomi(), http);
    }
}
