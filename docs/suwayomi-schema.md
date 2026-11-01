# Integração com o Suwayomi (GraphQL)

As operações usadas pelo MangaVerse foram conferidas por **introspecção do schema** do
Suwayomi Server **v2.4.2366** (imagem `ghcr.io/suwayomi/suwayomi-server:stable`, 30/09/2026)
e testadas contra um servidor real. Elas estão em
`backend/src/main/java/com/mangaverse/provider/suwayomi/SuwayomiClient.java`.

## Operações utilizadas

| Uso no MangaVerse | Operação GraphQL | Observações |
|---|---|---|
| Status / versão | `query { aboutServer { name version buildType } }` | |
| Fontes instaladas | `query { sources { nodes { id name lang displayName iconUrl ... extension { pkgName } } } }` | `id` é `LongString`; a fonte `0` (Local source) é ignorada |
| Pesquisa / populares / recentes | `mutation fetchSourceManga(input: {source, type: SEARCH\|POPULAR\|LATEST, query, page})` | retorna `mangas` e `hasNextPage` |
| Detalhes + capítulos | `mutation fetchMangaAndChapters(input: {id, fetchManga: true, fetchChapters: true})` | quando a fonte não tem capítulos, o servidor devolve o erro `No chapters found` **com dados parciais** — tratado como lista vazia |
| Páginas de um capítulo | `mutation fetchChapterPages(input: {chapterId})` | devolve caminhos relativos `/api/v1/manga/{id}/chapter/{n}/page/{i}` |
| Imagens (páginas, capas, ícones) | REST `GET /api/v1/...` | o backend só aceita caminhos que começam com `/api/v1/` |
| Extensões | `query { extensions { nodes { pkgName name lang versionName isInstalled hasUpdate isObsolete ... } } }` | a lista fica vazia até `fetchExtensions` ser chamado |
| Atualizar lista de extensões | `mutation fetchExtensions(input: {})` | |
| Instalar / remover / atualizar | `mutation updateExtension(input: {id: pkgName, patch: {install\|uninstall\|update: true}})` | retorna `extension: null` para pacotes desconhecidos |
| Repositórios (Keiyoushi etc.) | `query extensionStores`, `mutation addExtensionStore(input: {indexUrl})`, `mutation removeExtensionStore(input: {indexUrl})` | nesta versão os repositórios são "extension stores" |

O URL do Keiyoushi usado por padrão é
`https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json`
(o Suwayomi o normaliza para `.../index.pb`).

## Downloads

O Suwayomi possui um downloader próprio (`enqueueChapterDownloads`), mas ele grava no volume do
Suwayomi com outra estrutura de pastas. Para manter **uma única biblioteca** com o layout
`Mangas/<Série>/Capitulo 001.cbz`, verificação de integridade e controle de fila, o MangaVerse baixa
as páginas através do próprio Suwayomi (`fetchChapterPages` + `/api/v1/.../page/{i}`), ou seja,
usando exatamente o mecanismo da extensão, e monta o CBZ localmente.

## Como reconferir após atualizar o Suwayomi

```bash
curl -s -X POST http://localhost:4567/api/graphql -H 'Content-Type: application/json' \
  -d '{"query":"{ __schema { mutationType { fields { name } } } }"}' | python3 -m json.tool
```

Se algum campo usado acima mudar, ajuste as constantes em `SuwayomiClient` e rode
`./mvnw test` (os testes `SuwayomiProviderTest` cobrem o mapeamento das respostas).
