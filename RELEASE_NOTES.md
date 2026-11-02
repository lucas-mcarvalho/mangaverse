# Notas de versão

## 2026-11-02 — Tradução local de HQs e exportação em CBZ

Branch `develop`. A tradução com IA na GPU NVIDIA está separada, na branch
`feature/traducao-gpu` (veja [Tradução com IA na GPU](#tradução-com-ia-na-gpu-opcional)).

### Novidades

- **Tradução de HQs inglês → português, 100% local.** Na lista de edições ou no leitor, clique em
  **Traduzir → Preparar tradução**. O serviço `translator` encontra os balões, lê o texto, traduz e
  reescreve dentro do balão com fonte de quadrinhos. Nenhuma imagem sai do seu computador. O original
  é preservado; no leitor, alterne entre **Português / Original**.
- **Traduzir novamente.** Refaz uma edição já traduzida (por exemplo, depois de uma atualização do
  tradutor): **Traduzir → Traduzir novamente → Confirmar**.
- **Salvar ou baixar a edição traduzida em CBZ.** Com a edição pronta: **Salvar em pasta** (você escolhe
  a pasta na hora) ou **Baixar** pelo navegador.
- **Núcleos da CPU para o tradutor.** Em **Configurações → Tradução**, escolha quantos núcleos ele usa.
  Mais núcleos traduzem mais rápido, mas deixam o computador mais ocupado.
- **Tradutor desliga sozinho.** Os modelos (1 a 4 GB de RAM) são descarregados 5 minutos após a última
  página; parado, o serviço usa poucos MB e nenhuma CPU.

### Correções

- Fontes protegidas pelo Cloudflare (ex.: readcomicsonline): mais tempo para o Byparr resolver o
  desafio, que às vezes leva quase 2 minutos.
- Janelas (como a de tradução) não ficam mais embaixo da barra de filtros da página.
- Tradutor: balões que não eram traduzidos, linhas de balão partidas ao meio, fachadas escuras tratadas
  como balão preto, `?!` lido como `Z:`, aspas e travessões soltos vindos do OCR.

### Como rodar

Requisitos: Docker 24+ com Docker Compose v2, uns 6 GB livres em disco e internet no primeiro uso
(para baixar os modelos de tradução).

```bash
git clone https://github.com/lucas-mcarvalho/mangaverse.git
cd mangaverse
git switch develop
cp .env.example .env
# edite .env: POSTGRES_PASSWORD, MANGAVERSE_ADMIN_PASSWORD e as pastas da biblioteca
mkdir -p data/library data/extra
docker compose up -d --build
```

Acesse **http://localhost:5180**. Para usar no celular ou em outro computador da rede, defina
`FRONTEND_BIND=0.0.0.0` no `.env`, rode `docker compose up -d frontend` e acesse
`http://IP-DO-COMPUTADOR:5180`.

Na primeira inicialização o tradutor baixa os modelos para o volume `translation_models`. Acompanhe e
espere a mensagem **Tradutor local pronto** antes de traduzir:

```bash
docker compose logs -f translator
```

Já tem o projeto rodando? Atualize com:

```bash
git switch develop && git pull
docker compose up -d --build
```

Edições traduzidas antes desta versão continuam disponíveis; use **Traduzir novamente** para
aproveitar as correções do tradutor.

### Tempo de tradução

Na CPU, com 4 núcleos, cerca de 6 a 8 s por página (uma edição de 36 páginas leva uns 4 a 5 minutos).
Antes de traduzir, cada página é baixada da fonte. Na primeira vez que uma fonte com Cloudflare é
acessada depois de reiniciar o Docker, a resposta pode levar perto de 2 minutos; se der erro de
Cloudflare, espere uns 2 minutos e tente de novo — o Byparr continua resolvendo em segundo plano e a
próxima tentativa passa direto.

### Tradução com IA na GPU (opcional)

A branch `feature/traducao-gpu` troca o tradutor Argos por uma IA local (`qwen3:4b-instruct`, via
Ollama) que traduz a página inteira de uma vez, com contexto: menos inconsistências, gírias traduzidas
pelo sentido e nomes preservados. Ela fica fora da `develop` e da `main`; use se tiver uma GPU NVIDIA
com 6 GB ou mais.

1. Instale o NVIDIA Container Toolkit no host (Arch Linux; outras distribuições: veja a documentação
   da NVIDIA):

   ```bash
   sudo pacman -S nvidia-container-toolkit
   sudo nvidia-ctk runtime configure --runtime=docker
   sudo systemctl restart docker
   ```

2. Troque de branch e suba com o arquivo da GPU (a primeira vez baixa uns 20 GB entre imagens com CUDA
   e o modelo):

   ```bash
   git switch feature/traducao-gpu
   docker compose -f docker-compose.yml -f docker-compose.gpu.yml up -d --build
   ```

   Para não precisar repetir o `-f`, adicione ao `.env`:
   `COMPOSE_FILE=docker-compose.yml:docker-compose.gpu.yml`.

3. Na GPU, cada página leva de 5 a 10 s. Sem GPU a branch funciona, mas a IA roda na CPU (uns 45 s por
   página). Se o Ollama não responder, o tradutor usa o Argos automaticamente.

Para voltar à versão sem IA:

```bash
git switch develop
docker compose up -d --build --remove-orphans
```

### Limitações conhecidas

- Tradução automática: letras muito estilizadas, onomatopeias e texto sobre o desenho podem ficar em
  inglês ou com erros.
- A tradução depende de a fonte entregar as páginas; fontes com Cloudflare podem falhar na primeira
  tentativa após reiniciar.
