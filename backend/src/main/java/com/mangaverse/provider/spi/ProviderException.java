package com.mangaverse.provider.spi;

/** Falha ao se comunicar com um provedor externo (indisponível, timeout, resposta inválida). */
public class ProviderException extends RuntimeException {

    private final String providerCode;

    public ProviderException(String providerCode, String message) {
        super(message);
        this.providerCode = providerCode;
    }

    public ProviderException(String providerCode, String message, Throwable cause) {
        super(message, cause);
        this.providerCode = providerCode;
    }

    public String getProviderCode() {
        return providerCode;
    }
}
