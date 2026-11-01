package com.mangaverse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tarefas agendadas (fila de downloads e monitoramento). Desligável com mangaverse.scheduling.enabled=false. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "mangaverse.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
