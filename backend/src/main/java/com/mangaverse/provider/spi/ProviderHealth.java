package com.mangaverse.provider.spi;

public record ProviderHealth(Status status, String message) {

    public enum Status { ONLINE, OFFLINE, ERROR, NOT_CONFIGURED }

    public static ProviderHealth online(String message) {
        return new ProviderHealth(Status.ONLINE, message);
    }

    public static ProviderHealth offline(String message) {
        return new ProviderHealth(Status.OFFLINE, message);
    }

    public static ProviderHealth notConfigured(String message) {
        return new ProviderHealth(Status.NOT_CONFIGURED, message);
    }
}
