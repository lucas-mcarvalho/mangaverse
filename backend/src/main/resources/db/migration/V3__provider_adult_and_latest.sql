-- Fontes só de conteúdo adulto (hentai etc.): ficam escondidas enquanto o modo +18 estiver desligado.
ALTER TABLE providers ADD COLUMN adult BOOLEAN NOT NULL DEFAULT FALSE;
-- Se a fonte tem listagem de lançamentos recentes (usado no catálogo do Explorar).
ALTER TABLE providers ADD COLUMN supports_latest BOOLEAN NOT NULL DEFAULT TRUE;

-- Marca já as fontes adultas conhecidas pelo nome; a sincronização com o Suwayomi completa pelo aviso de conteúdo.
UPDATE providers SET adult = TRUE
 WHERE type = 'SUWAYOMI_SOURCE'
   AND name ~* '(hentai|porn|xxx|nsfw|erotic|er[oó]tico|doujin|lewd|\+18|18\+|quadrinhos de sexo|tsumino|pururin|luscious|8muses|hipercool)';
