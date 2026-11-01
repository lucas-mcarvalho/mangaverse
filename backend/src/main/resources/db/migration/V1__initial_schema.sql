-- MangaVerse: esquema inicial

CREATE TABLE users (
    id            UUID PRIMARY KEY,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT ck_users_role CHECK (role IN ('ADMIN', 'USER'))
);

CREATE TABLE providers (
    id                UUID PRIMARY KEY,
    code              VARCHAR(120) NOT NULL,
    name              VARCHAR(200) NOT NULL,
    type              VARCHAR(30)  NOT NULL,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    priority          INTEGER      NOT NULL DEFAULT 100,
    language          VARCHAR(20),
    external_ref      VARCHAR(120),
    icon_url          TEXT,
    supports_search   BOOLEAN      NOT NULL DEFAULT TRUE,
    supports_download BOOLEAN      NOT NULL DEFAULT FALSE,
    metadata_only     BOOLEAN      NOT NULL DEFAULT FALSE,
    last_status       VARCHAR(20)  NOT NULL DEFAULT 'UNKNOWN',
    last_error        TEXT,
    last_checked_at   TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_providers_code UNIQUE (code),
    CONSTRAINT ck_providers_type CHECK (type IN ('MANGADEX', 'COMICVINE', 'SUWAYOMI_SOURCE', 'LOCAL')),
    CONSTRAINT ck_providers_status CHECK (last_status IN ('UNKNOWN', 'ONLINE', 'OFFLINE', 'ERROR', 'NOT_CONFIGURED'))
);

CREATE TABLE contents (
    id                 UUID PRIMARY KEY,
    title              VARCHAR(500) NOT NULL,
    normalized_title   VARCHAR(500) NOT NULL,
    content_type       VARCHAR(20)  NOT NULL,
    description        TEXT,
    authors            TEXT,
    artists            TEXT,
    publisher          VARCHAR(200),
    genres             TEXT,
    characters         TEXT,
    language           VARCHAR(20),
    publication_status VARCHAR(30),
    cover_url          TEXT,
    year               INTEGER,
    local_path         TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_contents_type CHECK (content_type IN ('MANGA', 'MANHWA', 'MANHUA', 'COMIC'))
);
CREATE INDEX ix_contents_normalized_title ON contents (normalized_title);
CREATE UNIQUE INDEX uq_contents_local_path ON contents (local_path) WHERE local_path IS NOT NULL;

CREATE TABLE content_sources (
    id             UUID PRIMARY KEY,
    content_id     UUID         NOT NULL REFERENCES contents (id) ON DELETE CASCADE,
    provider_id    UUID         NOT NULL REFERENCES providers (id) ON DELETE CASCADE,
    external_id    VARCHAR(200) NOT NULL,
    url            TEXT,
    title          VARCHAR(500),
    language       VARCHAR(20),
    cover_url      TEXT,
    last_synced_at TIMESTAMPTZ,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_content_sources_provider_external UNIQUE (provider_id, external_id)
);
CREATE INDEX ix_content_sources_content ON content_sources (content_id);

CREATE TABLE chapters (
    id                UUID PRIMARY KEY,
    content_id        UUID        NOT NULL REFERENCES contents (id) ON DELETE CASCADE,
    content_source_id UUID REFERENCES content_sources (id) ON DELETE CASCADE,
    external_id       VARCHAR(200),
    number            NUMERIC(10, 3),
    number_label      VARCHAR(50),
    volume            VARCHAR(50),
    title             VARCHAR(500),
    language          VARCHAR(20),
    scanlator         VARCHAR(300),
    published_at      TIMESTAMPTZ,
    page_count        INTEGER,
    readable          BOOLEAN     NOT NULL DEFAULT TRUE,
    downloadable      BOOLEAN     NOT NULL DEFAULT FALSE,
    external_url      TEXT,
    local_path        TEXT,
    file_size         BIGINT,
    checksum          VARCHAR(64),
    downloaded_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_chapters_source_external UNIQUE (content_source_id, external_id),
    CONSTRAINT ck_chapters_origin CHECK (content_source_id IS NOT NULL OR local_path IS NOT NULL)
);
CREATE INDEX ix_chapters_content_number ON chapters (content_id, number);
CREATE UNIQUE INDEX uq_chapters_local_path ON chapters (local_path) WHERE local_path IS NOT NULL;

CREATE TABLE downloads (
    id               UUID PRIMARY KEY,
    chapter_id       UUID        NOT NULL REFERENCES chapters (id) ON DELETE CASCADE,
    status           VARCHAR(20) NOT NULL,
    progress         INTEGER     NOT NULL DEFAULT 0,
    total_pages      INTEGER     NOT NULL DEFAULT 0,
    downloaded_pages INTEGER     NOT NULL DEFAULT 0,
    bytes_downloaded BIGINT      NOT NULL DEFAULT 0,
    speed_bps        BIGINT      NOT NULL DEFAULT 0,
    error_message    TEXT,
    attempts         INTEGER     NOT NULL DEFAULT 0,
    file_path        TEXT,
    auto_triggered   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_downloads_status CHECK (status IN ('QUEUED', 'DOWNLOADING', 'PAUSED', 'COMPLETED', 'FAILED', 'CANCELLED')),
    CONSTRAINT ck_downloads_progress CHECK (progress BETWEEN 0 AND 100)
);
-- Impede downloads ativos duplicados para o mesmo capítulo
CREATE UNIQUE INDEX uq_downloads_active_chapter ON downloads (chapter_id)
    WHERE status IN ('QUEUED', 'DOWNLOADING', 'PAUSED');
CREATE INDEX ix_downloads_status_created ON downloads (status, created_at);

CREATE TABLE categories (
    id         UUID PRIMARY KEY,
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    name       VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_categories_user_name UNIQUE (user_id, name)
);

CREATE TABLE library_entries (
    id              UUID PRIMARY KEY,
    user_id         UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    content_id      UUID        NOT NULL REFERENCES contents (id) ON DELETE CASCADE,
    category_id     UUID REFERENCES categories (id) ON DELETE SET NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PLAN_TO_READ',
    favorite        BOOLEAN     NOT NULL DEFAULT FALSE,
    monitored       BOOLEAN     NOT NULL DEFAULT FALSE,
    auto_download   BOOLEAN     NOT NULL DEFAULT FALSE,
    added_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_read_at    TIMESTAMPTZ,
    last_checked_at TIMESTAMPTZ,
    CONSTRAINT uq_library_user_content UNIQUE (user_id, content_id),
    CONSTRAINT ck_library_status CHECK (status IN ('READING', 'PLAN_TO_READ', 'COMPLETED', 'PAUSED', 'DROPPED'))
);
CREATE INDEX ix_library_monitored ON library_entries (monitored) WHERE monitored;

CREATE TABLE reading_progress (
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    content_id  UUID        NOT NULL REFERENCES contents (id) ON DELETE CASCADE,
    chapter_id  UUID        NOT NULL REFERENCES chapters (id) ON DELETE CASCADE,
    page        INTEGER     NOT NULL DEFAULT 0,
    total_pages INTEGER     NOT NULL DEFAULT 0,
    completed   BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_progress_user_chapter UNIQUE (user_id, chapter_id),
    CONSTRAINT ck_progress_page CHECK (page >= 0)
);
CREATE INDEX ix_progress_user_updated ON reading_progress (user_id, updated_at DESC);

CREATE TABLE app_settings (
    id            UUID PRIMARY KEY,
    setting_key   VARCHAR(100) NOT NULL,
    setting_value TEXT,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_settings_key UNIQUE (setting_key)
);

CREATE TABLE update_history (
    id          UUID PRIMARY KEY,
    content_id  UUID        NOT NULL REFERENCES contents (id) ON DELETE CASCADE,
    chapter_id  UUID REFERENCES chapters (id) ON DELETE SET NULL,
    provider_id UUID REFERENCES providers (id) ON DELETE SET NULL,
    event_type  VARCHAR(30) NOT NULL,
    message     TEXT,
    seen        BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_update_history_type CHECK (event_type IN ('NEW_CHAPTER', 'CHECK_FAILED', 'DOWNLOAD_COMPLETED', 'DOWNLOAD_FAILED'))
);
CREATE INDEX ix_update_history_created ON update_history (created_at DESC);

INSERT INTO providers (id, code, name, type, enabled, priority, language, supports_search, supports_download, metadata_only)
VALUES ('00000000-0000-0000-0000-000000000001', 'mangadex', 'MangaDex', 'MANGADEX', TRUE, 10, NULL, TRUE, TRUE, FALSE),
       ('00000000-0000-0000-0000-000000000002', 'comicvine', 'Comic Vine', 'COMICVINE', TRUE, 50, 'en', TRUE, FALSE, TRUE),
       ('00000000-0000-0000-0000-000000000003', 'local', 'Arquivos locais', 'LOCAL', TRUE, 1, NULL, FALSE, FALSE, FALSE);
