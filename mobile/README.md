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
3. No app, abra **Extensões → Disponíveis** e instale as fontes que você usa (ex.: Manga Livre.to, Mugiwaras Oficial,
   HQ Now!, MangaDex).

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

- **Biblioteca:** títulos favoritos com capítulos não lidos, filtro, ordenação e "procurar capítulos novos" em todos.
- **Histórico:** no topo, a obra que você está lendo agora (progresso da obra inteira, Continuar/Próximo capítulo,
  Obra completa e Adicionar à biblioteca); abaixo, o último capítulo lido de cada título.
- **Explorar:** fontes instaladas (filtradas pelos idiomas escolhidos), populares/recentes/busca em cada fonte e
  pesquisa em todas as fontes ao mesmo tempo. O botão **Fontes** escolhe em quais fontes a pesquisa procura (fica
  salvo; atalhos "Só PT-BR" e "Sem +18"). As barras de pesquisa têm um **X** para limpar.
- **Extensões:** instalar, atualizar e remover; idiomas, conteúdo adulto e repositórios. As extensões só de conteúdo
  adulto ficam separadas na aba **+18** (a aba some ao desligar "Mostrar extensões adultas").
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
- Filtros avançados das fontes (gênero, status…) ainda não aparecem; a busca por texto funciona.
- Sem sincronização com o servidor MangaVerse: biblioteca e progresso ficam só no celular.
