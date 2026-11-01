package com.mangaverse.common;

import java.time.Duration;

/**
 * Limitador simples por intervalo mínimo entre requisições (bloqueante).
 * Seguro para uso concorrente: cada chamada reserva o próximo horário disponível.
 */
public class RateLimiter {

    private final long intervalNanos;
    private long nextSlot = System.nanoTime();

    public RateLimiter(double permitsPerSecond) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond deve ser positivo");
        }
        this.intervalNanos = (long) (1_000_000_000L / permitsPerSecond);
    }

    public void acquire() {
        long waitNanos;
        synchronized (this) {
            long now = System.nanoTime();
            long slot = Math.max(now, nextSlot);
            nextSlot = slot + intervalNanos;
            waitNanos = slot - now;
        }
        if (waitNanos > 0) {
            try {
                Thread.sleep(Duration.ofNanos(waitNanos));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrompido aguardando limite de requisições", e);
            }
        }
    }
}
