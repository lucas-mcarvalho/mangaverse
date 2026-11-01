package com.mangaverse.user;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mangaverse.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contextRepository;
    private final CurrentUser currentUser;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;

    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contextRepository,
                          CurrentUser currentUser, AppUserRepository users, PasswordEncoder encoder) {
        this.authenticationManager = authenticationManager;
        this.contextRepository = contextRepository;
        this.currentUser = currentUser;
        this.users = users;
        this.encoder = encoder;
    }

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) {
    }

    public record ChangePasswordRequest(@NotBlank String currentPassword, @NotBlank @Size(min = 8, max = 200) String newPassword) {
    }

    public record UserView(String id, String username, String role) {
    }

    /** Garante que o cookie XSRF-TOKEN seja emitido antes do login. */
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName());
    }

    @PostMapping("/login")
    public UserView login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        Authentication auth = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(body.username(), body.password()));
        HttpSession old = request.getSession(false);
        if (old != null) {
            old.invalidate(); // proteção contra fixação de sessão
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contextRepository.saveContext(context, request, response);
        AppUser user = users.findByUsername(auth.getName()).orElseThrow();
        return new UserView(user.getId().toString(), user.getUsername(), user.getRole());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public UserView me() {
        AppUser user = currentUser.get();
        return new UserView(user.getId().toString(), user.getUsername(), user.getRole());
    }

    @PostMapping("/password")
    @Transactional
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        AppUser user = currentUser.get();
        if (!encoder.matches(body.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest("Senha atual incorreta.");
        }
        user.setPasswordHash(encoder.encode(body.newPassword()));
        users.save(user);
        return ResponseEntity.noContent().build();
    }
}
