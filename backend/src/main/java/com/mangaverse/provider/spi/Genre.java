package com.mangaverse.provider.spi;

/** Gênero/categoria oferecido por uma fonte. O {@code id} só tem sentido para a própria fonte. */
public record Genre(String id, String name) {
}
