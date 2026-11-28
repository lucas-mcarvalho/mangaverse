# MangaVerse para Android

App nativo (Kotlin + Jetpack Compose) **independente**: não precisa do servidor MangaVerse. Ele busca os títulos direto
dos sites usando as **extensões Tachiyomi/Mihon** (repositório Keiyoushi), e guarda biblioteca, progresso, histórico e
downloads no próprio celular.

## Instalar

1. Use `dist/MangaVerse.apk` ou gere de novo:
   ```bash
   ./gradlew :app:assembleRelease
   cp app/build/outputs/apk/release/app-release.apk dist/MangaVerse.apk
   ```
2. Copie o APK para o celular e instale (permita "instalar apps desconhecidos"). Android 8.0 ou mais novo.
3. No app, abra **Explorar → ícone de extensões → Disponíveis** e instale as fontes que você usa (ex.: Manga Livre.to,
   Mugiwaras Oficial, HQ Now!, MangaDex).

## Atualização automática

O app procura versões novas nas **Releases do GitHub** do projeto (`lucas-mcarvalho/mangaverse`, configurável em
`UPDATE_REPO` no `app/build.gradle.kts`): pega a release mais nova que tenha um `.apk` anexado e compara a versão do nome
da tag (ex.: `mobile-v2.3`) com a instalada.

- Ao abrir o app (no máximo a cada 6 h), se houver versão nova, ele baixa e instala sozinho. Dá para desligar em
  **Início → ⚙ Ajustes** (só avisar, ou nem procurar) e procurar na hora pelo botão "Procurar atualização".
- Antes de instalar, o app confere se o APK é do próprio MangaVerse, se é mais novo e se tem a **mesma assinatura**.
- Na primeira vez o Android pede para permitir "Instalar apps desconhecidos" para o MangaVerse e confirmar a
  instalação. A partir do Android 12, depois que o próprio app instalou uma versão, as seguintes podem entrar sem
  pergunta.

Para publicar uma versão: aumente `versionCode` e `versionName` em `app/build.gradle.kts` e rode
`./release.sh "notas da versão"` (precisa do `gh` logado). Gere sempre na mesma máquina: o APK é assinado com a chave de
debug dela, e o Android recusa atualizar um app com assinatura diferente.

## Conteúdo +18

Extensões de hentai/conteúdo adulto ficam **completamente separadas** do resto do app:

- O **modo +18 vem desligado**. Para ligar: Extensões → ícone de ajustes → "Modo +18", com confirmação de idade.
- Desligado, as extensões adultas não aparecem na lista, não podem ser instaladas, **não são carregadas** e os títulos
  delas somem do Início, da pesquisa, do Explorar, da biblioteca e do histórico.
- Ligado, elas ficam só na aba **+18** das extensões. Os arquivos ficam numa pasta própria (`files/exts-adult`); ao
  desligar o modo dá para só escondê-las ou apagá-las.
- Conta como adulta a extensão com aviso **NSFW** no repositório (formato novo do Keiyoushi, onde as fontes comuns com
  algum título adulto vêm como "MIXED") ou com nome/pacote de conteúdo adulto (hentai, nhentai, doujin, porn…). O selo
  "nsfw" do formato antigo (`index.min.json`) sozinho não basta, porque marca também fontes comuns.

## Como as extensões funcionam

- A lista vem do repositório do Keiyoushi (`index.json`); dá para trocar ou adicionar repositórios no formato do Mihon
  em Extensões → ajustes.
- Ao instalar, o app baixa o APK da extensão, confere se a assinatura é a do repositório e carrega as classes direto do
  arquivo (como as "extensões privadas" do Mihon). Nada é instalado no sistema.
- Suporta extensions-lib 1.4 a 1.6. A camada de compatibilidade (`eu.kanade.tachiyomi.*`) foi adaptada do
  [Mihon](https://github.com/mihonapp/mihon) (Apache 2.0, ver `LICENSE-mihon.txt`).
- Sites com Cloudflare: o app tenta resolver o desafio sozinho numa WebView invisível. Se o site pedir captcha, use o
  ícone de globo (abre o site no navegador interno, que compartilha os cookies com a extensão).

## Telas

- **Início:** catálogo da fonte escolhida (toque no nome dela no topo para trocar): continue lendo, **Em alta**,
  **Lançamentos recentes**, **Categorias**, **Populares** e novidades de outras fontes instaladas. "Ver tudo" abre a
  lista completa com rolagem infinita; puxe para baixo para atualizar.
- **Pesquisar:** pesquisa em todas as fontes ao mesmo tempo (ou só nas escolhidas), com pesquisas recentes e atalhos
  para as categorias.
- **Biblioteca:** títulos favoritos com capítulos não lidos, filtro, ordenação e "procurar capítulos novos" em todos.
- **Histórico:** no topo, a obra que você está lendo agora (progresso da obra inteira, Continuar/Próximo capítulo,
  Obra completa e Adicionar à biblioteca); abaixo, o último capítulo lido de cada título.
- **Explorar:** fontes instaladas (filtradas pelos idiomas escolhidos); em cada fonte, Populares, Lançamentos,
  **Categorias** (os gêneros vêm dos filtros da própria extensão) e busca. O ícone no topo abre as **Extensões**.
- **Extensões:** instalar, atualizar e remover; idiomas, modo +18 e repositórios. As extensões só de conteúdo adulto
  ficam na aba **+18**, que só existe com o modo +18 ligado.
- **Título:** detalhes, adicionar à biblioteca, continuar, lista de capítulos (segure para selecionar vários: marcar
  lido/não lido, baixar, apagar download) e menu para baixar os próximos 5 ou todos os não lidos.
- **Downloads:** fila com progresso. Os capítulos baixados abrem sem internet.

## Leitor

- Modos **rolagem contínua** (webtoon), **esquerda → direita**, **direita → esquerda** e **páginas na vertical**,
  trocados pelo botão de modo na barra de cima. O modo fica salvo por título; nos ajustes dá para fixar o modo dos
  títulos novos. No automático, o modo vem dos gêneros, e tiras longas (manhwa) passam sozinhas para a rolagem contínua.
- Tiras muito altas são desenhadas em fatias (passavam do limite de imagem da GPU e apareciam cortadas ou distorcidas).
- MangaDex: capítulos oficiais (ex.: One Piece em português, do MANGA Plus) não têm páginas na MangaDex e davam erro
  403. Eles aparecem marcados como "Só no MANGA Plus", com atalho para procurar o título em outras fontes.
- **Zoom** com pinça ou toque duplo; com zoom, um dedo arrasta a página.
- **Lista de capítulos** no próprio leitor (ícone de lista na barra de baixo) para pular para qualquer capítulo.
- **Toques** nas bordas viram a página; o centro mostra/esconde os controles. **Teclas de volume** viram a página.
- Pré-carrega as próximas páginas e o começo do próximo capítulo; cache de 512 MB de imagens.
- Progresso salvo no aparelho; o capítulo é marcado como lido ao chegar ao fim.
- Ajustes: fundo preto/cinza/branco, espaço entre páginas, tela ligada e número da página.

## Limitações

- Downloads só andam com o app aberto (não há serviço em segundo plano).
- Ajustes próprios das extensões (`ConfigurableSource`, ex.: qualidade de imagem da MangaDex) ainda não têm tela;
  valem os padrões de cada extensão.
- Dos filtros das fontes, só o de gênero/categoria tem tela; os outros (status, ordem…) ainda não aparecem.
- Sem sincronização com o servidor MangaVerse: biblioteca e progresso ficam só no celular.
