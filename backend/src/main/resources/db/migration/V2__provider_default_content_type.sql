-- Tipo de conteúdo padrão por fonte (ex.: extensões de HQ sempre como COMIC).
-- NULL = ainda não definido; 'AUTO' = deduzir pelos gêneros informados pela fonte.
ALTER TABLE providers ADD COLUMN default_content_type VARCHAR(20);
ALTER TABLE providers ADD CONSTRAINT ck_providers_default_type
    CHECK (default_content_type IS NULL OR default_content_type IN ('AUTO', 'MANGA', 'MANHWA', 'MANHUA', 'COMIC'));
