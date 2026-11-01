package com.mangaverse.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.mangaverse.common.ApiException;

class PathGuardTest {

    @TempDir
    Path tmp;
    Path root;
    Path outside;
    PathGuard guard;

    @BeforeEach
    void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("library"));
        outside = Files.createDirectories(tmp.resolve("secret"));
        Files.writeString(outside.resolve("passwd"), "x");
        guard = new PathGuard(List.of(root.toString()));
    }

    @Test
    void allowsPathsInsideRootIncludingNonExistingOnes() {
        Path resolved = guard.requireAllowed(root.resolve("Mangas/One Piece").toString());
        assertThat(resolved.startsWith(guard.roots().get(0))).isTrue();
        assertThat(resolved.getFileName().toString()).isEqualTo("One Piece");
        assertThat(guard.isAllowed(root)).isTrue();
    }

    @Test
    void blocksTraversalOutsideRoot() {
        assertThatThrownBy(() -> guard.requireAllowed(root + "/../secret/passwd"))
                .isInstanceOf(ApiException.class).hasMessageContaining("fora dos diretórios permitidos");
        assertThatThrownBy(() -> guard.requireAllowed("/etc/passwd")).isInstanceOf(ApiException.class);
        assertThat(guard.isAllowed(outside)).isFalse();
    }

    @Test
    void blocksPrefixConfusion() throws Exception {
        Path sibling = Files.createDirectories(tmp.resolve("library-evil"));
        assertThat(guard.isAllowed(sibling)).isFalse();
    }

    @Test
    void blocksSymlinkEscapingRoot() throws Exception {
        Path link = Files.createSymbolicLink(root.resolve("atalho"), outside);
        assertThat(guard.isAllowed(link)).isFalse();
        assertThat(guard.isAllowed(link.resolve("passwd"))).isFalse();
    }

    @Test
    void rejectsNullBytesAndBlank() {
        assertThatThrownBy(() -> guard.requireAllowed("abc\0def")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> guard.requireAllowed("  ")).isInstanceOf(ApiException.class);
    }

    @Test
    void safeResolveBlocksZipSlip() {
        Path base = root.resolve("extract");
        assertThat(PathGuard.safeResolve(base, "cap1/001.jpg")).isEqualTo(base.resolve("cap1/001.jpg").toAbsolutePath().normalize());
        assertThatThrownBy(() -> PathGuard.safeResolve(base, "../../evil.sh")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PathGuard.safeResolve(base, "a/../../evil.sh")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PathGuard.safeResolve(base, "/etc/cron.d/x")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PathGuard.safeResolve(base, "..\\..\\evil.bat")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> PathGuard.safeResolve(base, "C:\\Windows\\x.dll")).isInstanceOf(SecurityException.class);
    }
}
