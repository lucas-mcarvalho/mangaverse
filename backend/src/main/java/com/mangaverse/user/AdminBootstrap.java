package com.mangaverse.user;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.mangaverse.config.AppProperties;

/** Cria o usuário administrador na primeira inicialização. */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AppProperties properties;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder encoder, AppProperties properties) {
        this.users = users;
        this.encoder = encoder;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String username = properties.admin().username();
        String password = properties.admin().password();
        if (password == null || password.isBlank()) {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("MANGAVERSE_ADMIN_PASSWORD não definido. Senha gerada para o usuário '{}': {}  (altere em Configurações)", username, password);
        }
        users.save(new AppUser(username, encoder.encode(password), "ADMIN"));
        log.info("Usuário administrador '{}' criado.", username);
    }
}
