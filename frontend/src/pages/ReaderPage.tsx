import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  ArrowLeft, ChevronLeft, ChevronRight, Columns2, Maximize, Minimize, MoveHorizontal, Rows3, Settings2, Square,
  ZoomIn, ZoomOut,
} from 'lucide-react'
import { useCallback, useEffect, useMemo, useRef, useState, type TouchEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { api, errorMessage } from '../api/client'
import { saveSourcePreference } from '../lib/sourcePreference'
import type { ReaderChapter } from '../api/types'
import { ErrorState, PageLoader, Spinner } from '../components/ui'
import { cx } from '../lib/format'
import { TranslationControls, useTranslation } from '../components/TranslationControls'

type Mode = 'vertical' | 'single' | 'double'
type Direction = 'ltr' | 'rtl'
type Fit = 'width' | 'height' | 'original'
type Bg = 'black' | 'gray' | 'white'
interface Prefs { mode: Mode; direction: Direction; fit: Fit; zoom: number; bg: Bg; gap: boolean }

const BG: Record<Bg, { label: string; className: string }> = {
  black: { label: 'Preto', className: 'bg-black' },
  gray: { label: 'Cinza', className: 'bg-ink-800' },
  white: { label: 'Branco', className: 'bg-white' },
}

function loadPrefs(contentId: string, chapter: ReaderChapter): Prefs {
  try {
    const raw = localStorage.getItem(`reader:${contentId}`)
    if (raw) return { zoom: 1, bg: 'black', gap: false, ...JSON.parse(raw) }
  } catch {
    // preferências indisponíveis: usa o padrão do tipo de conteúdo
  }
  if (chapter.defaultMode === 'vertical') return { mode: 'vertical', direction: 'ltr', fit: 'width', zoom: 1, bg: 'black', gap: false }
  return { mode: 'single', direction: chapter.defaultMode === 'rtl' ? 'rtl' : 'ltr', fit: 'height', zoom: 1, bg: 'black', gap: false }
}

function savePrefs(contentId: string, prefs: Prefs) {
  try {
    localStorage.setItem(`reader:${contentId}`, JSON.stringify(prefs))
  } catch {
    // ignora (modo privado)
  }
}

export default function ReaderPage() {
  const { chapterId = '' } = useParams()
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['reader', chapterId],
    queryFn: async () => (await api.get<ReaderChapter>(`/reader/chapters/${chapterId}`)).data,
    staleTime: Infinity,
  })
  if (isLoading) return <div className="flex h-full items-center justify-center bg-black"><PageLoader label="Abrindo capítulo…" /></div>
  if (error || !data) {
    return (
      <div className="flex h-full flex-col items-center justify-center gap-4 bg-black p-4">
        <ErrorState message={errorMessage(error)} onRetry={refetch} />
        <Link to="/" className="btn-ghost"><ArrowLeft className="h-4 w-4" /> Voltar</Link>
      </div>
    )
  }
  return <Reader key={data.chapterId} chapter={data} />
}

function Reader({ chapter }: { chapter: ReaderChapter }) {
  const [params, setParams] = useSearchParams()
  const translation = useTranslation(chapter.chapterId)
  const translated = params.get('traducao') === 'pt-br'
  const translationReady = translation.data?.status === 'READY'
  const displayPages = useMemo(() => translated && translationReady
    ? chapter.pages.map((_, index) => `/api/translations/chapters/${chapter.chapterId}/pages/${index}`)
    : chapter.pages, [chapter.chapterId, chapter.pages, translated, translationReady])
  useEffect(() => {
    if (!chapter.local && chapter.providerCode) saveSourcePreference(chapter.contentId, chapter.providerCode)
  }, [chapter.contentId, chapter.providerCode, chapter.local])
  const navigate = useNavigate()
  const [prefs, setPrefsState] = useState<Prefs>(() => loadPrefs(chapter.contentId, chapter))
  const [page, setPage] = useState(chapter.startPage)
  const [ui, setUi] = useState(true)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const [ended, setEnded] = useState(false)
  const [fullscreen, setFullscreen] = useState(!!document.fullscreenElement)
  const total = chapter.pageCount
  const step = prefs.mode === 'double' ? 2 : 1
  const rtl = prefs.direction === 'rtl'
  const verticalRef = useRef<HTMLDivElement>(null)
  const touchStart = useRef<{ x: number; y: number; t: number } | null>(null)

  const setPrefs = (patch: Partial<Prefs>) => setPrefsState((p) => {
    const next = { ...p, ...patch }
    savePrefs(chapter.contentId, next)
    return next
  })

  // Salvamento automático do progresso (com debounce)
  const save = useMutation({
    mutationFn: (p: { page: number; completed?: boolean }) =>
      api.put(`/reader/chapters/${chapter.chapterId}/progress`, { page: p.page, totalPages: total, completed: p.completed }),
  })
  const saveRef = useRef(save.mutate)
  saveRef.current = save.mutate
  useEffect(() => {
    const id = setTimeout(() => saveRef.current({ page }), 700)
    return () => clearTimeout(id)
  }, [page])

  // Começa a ler sem a interface na frente (toque no centro mostra de novo)
  useEffect(() => {
    const id = setTimeout(() => { setUi(false); setSettingsOpen(false) }, 2500)
    return () => clearTimeout(id)
  }, [])

  // Pré-carrega as próximas páginas
  useEffect(() => {
    for (let i = page + 1; i <= Math.min(total - 1, page + 4); i++) {
      const img = new Image()
      img.src = displayPages[i]
    }
  }, [page, total, displayPages])

  // Perto do fim, já busca o próximo capítulo e as primeiras páginas dele
  const queryClient = useQueryClient()
  useEffect(() => {
    const next = chapter.nextChapterId
    if (!next || page < total - 3) return
    queryClient.fetchQuery({
      queryKey: ['reader', next],
      queryFn: async () => (await api.get<ReaderChapter>(`/reader/chapters/${next}`)).data,
      staleTime: Infinity,
    }).then((c) => c.pages.slice(0, 3).forEach((src) => { const img = new Image(); img.src = src }))
      .catch(() => undefined)
  }, [page, total, chapter.nextChapterId, queryClient])

  // Modo vertical: chegar ao fim marca o capítulo como lido (no paginado isso acontece ao passar da última página)
  const endRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (prefs.mode !== 'vertical' || !endRef.current) return
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.some((e) => e.isIntersecting)
      if (visible && !ended) {
        saveRef.current({ page: total - 1, completed: true })
        setEnded(true)
      } else if (!visible && ended) {
        setEnded(false) // voltou a rolar para cima
      }
    }, { threshold: 0.3 })
    observer.observe(endRef.current)
    return () => observer.disconnect()
  }, [prefs.mode, total, ended])

  /** Modo vertical: rola quase uma tela inteira (teclado e toque em cima/embaixo). */
  const scrollScreen = useCallback((dir: 1 | -1) => {
    const el = verticalRef.current
    if (el) el.scrollBy({ top: dir * el.clientHeight * 0.85, behavior: 'smooth' })
  }, [])

  const goNext = useCallback(() => {
    if (page + step >= total) {
      if (!ended) {
        saveRef.current({ page: total - 1, completed: true })
        setEnded(true)
      } else if (chapter.nextChapterId) {
        navigate(`/ler/${chapter.nextChapterId}`)
      }
      return
    }
    setPage((p) => Math.min(total - 1, p + step))
  }, [page, step, total, ended, chapter.nextChapterId, navigate])

  const goPrev = useCallback(() => {
    if (ended) {
      setEnded(false)
      return
    }
    if (page === 0) {
      if (chapter.prevChapterId) navigate(`/ler/${chapter.prevChapterId}`)
      return
    }
    setPage((p) => Math.max(0, p - step))
  }, [page, step, ended, chapter.prevChapterId, navigate])

  const toggleFullscreen = useCallback(() => {
    if (document.fullscreenElement) document.exitFullscreen().catch(() => undefined)
    else document.documentElement.requestFullscreen().catch(() => undefined)
  }, [])

  useEffect(() => {
    const onFs = () => setFullscreen(!!document.fullscreenElement)
    document.addEventListener('fullscreenchange', onFs)
    return () => document.removeEventListener('fullscreenchange', onFs)
  }, [])

  // Navegação por teclado
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLSelectElement) return
      const vertical = prefs.mode === 'vertical'
      switch (e.key) {
        case 'ArrowRight': e.preventDefault(); if (rtl) goPrev(); else goNext(); break
        case 'ArrowLeft': e.preventDefault(); if (rtl) goNext(); else goPrev(); break
        case ' ': case 'PageDown': e.preventDefault(); if (vertical) scrollScreen(e.shiftKey ? -1 : 1); else goNext(); break
        case 'PageUp': e.preventDefault(); if (vertical) scrollScreen(-1); else goPrev(); break
        case 'ArrowDown': if (!vertical) { e.preventDefault(); goNext() } break
        case 'ArrowUp': if (!vertical) { e.preventDefault(); goPrev() } break
        case 'f': case 'F': toggleFullscreen(); break
        case '+': case '=': setPrefs({ zoom: Math.min(3, +(prefs.zoom + 0.25).toFixed(2)) }); break
        case '-': setPrefs({ zoom: Math.max(0.5, +(prefs.zoom - 0.25).toFixed(2)) }); break
        case '0': setPrefs({ zoom: 1 }); break
        case 'Escape': if (!document.fullscreenElement) navigate(`/conteudo/${chapter.contentId}`); break
        case 'n': case 'N': if (chapter.nextChapterId) navigate(`/ler/${chapter.nextChapterId}`); break
        case 'p': case 'P': if (chapter.prevChapterId) navigate(`/ler/${chapter.prevChapterId}`); break
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefs, rtl, goNext, goPrev, toggleFullscreen, chapter, scrollScreen])

  // Modo vertical: rola até a página inicial e acompanha a página visível
  useEffect(() => {
    if (prefs.mode !== 'vertical' || !verticalRef.current) return
    const container = verticalRef.current
    const target = container.querySelector<HTMLElement>(`[data-page="${page}"]`)
    target?.scrollIntoView({ block: 'start' })
    const observer = new IntersectionObserver((entries) => {
      const visible = entries.filter((e) => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)
      if (visible.length) setPage(Number((visible[0].target as HTMLElement).dataset.page))
    }, { rootMargin: '-45% 0px -45% 0px' })
    container.querySelectorAll('[data-page]').forEach((el) => observer.observe(el))
    return () => observer.disconnect()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [prefs.mode])

  // Página dupla começa em índice par
  useEffect(() => {
    if (prefs.mode === 'double' && page % 2 === 1) setPage((p) => p - 1)
  }, [prefs.mode, page])

  const onTouchStart = (e: TouchEvent) => {
    const t = e.touches[0]
    touchStart.current = { x: t.clientX, y: t.clientY, t: Date.now() }
  }
  const onTouchEnd = (e: TouchEvent) => {
    const start = touchStart.current
    touchStart.current = null
    if (!start || prefs.mode === 'vertical' || prefs.zoom > 1) return
    const t = e.changedTouches[0]
    const dx = t.clientX - start.x
    const dy = t.clientY - start.y
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy) * 1.5 && Date.now() - start.t < 600) {
      const forward = dx < 0 ? !rtl : rtl
      if (forward) goNext(); else goPrev()
    }
  }

  /**
   * Toque/clique: no paginado, terço esquerdo/direito vira a página; no vertical, terço de cima/baixo rola a tela.
   * O centro mostra/oculta a interface.
   */
  const onZoneClick = (e: React.MouseEvent) => {
    if (e.detail > 1) return // parte de um duplo clique (zoom)
    if (settingsOpen) { setSettingsOpen(false); return }
    if (prefs.mode === 'vertical') {
      const y = e.clientY / window.innerHeight
      if (y < 0.3) scrollScreen(-1)
      else if (y > 0.7) scrollScreen(1)
      else setUi((v) => !v)
      return
    }
    const x = e.clientX / window.innerWidth
    if (x > 0.33 && x < 0.67) {
      setUi((v) => !v)
      return
    }
    const leftSide = x <= 0.33
    if (leftSide === rtl) goNext(); else goPrev()
  }

  /** Duplo clique/toque: aproxima (2×) ou volta ao tamanho normal. */
  const onDoubleClick = () => setPrefs({ zoom: prefs.zoom > 1 ? 1 : 2 })

  const imgClass = useMemo(() => {
    if (prefs.fit === 'height') return 'max-h-[100dvh] w-auto'
    if (prefs.fit === 'width') return 'w-full h-auto'
    return 'max-w-none'
  }, [prefs.fit])

  const pagesToShow = prefs.mode === 'double' ? [page, page + 1].filter((i) => i < total) : [page]
  const ordered = rtl ? [...pagesToShow].reverse() : pagesToShow

  return (
    <div className={cx('fixed inset-0 select-none text-zinc-100', BG[prefs.bg].className)} onTouchStart={onTouchStart} onTouchEnd={onTouchEnd}>
      {prefs.mode === 'vertical' ? (
        <div ref={verticalRef} className="h-full overflow-y-auto" onClick={onZoneClick} onDoubleClick={onDoubleClick}>
          <div className={cx('mx-auto flex flex-col items-center', prefs.gap && 'gap-2')} style={{ width: `min(100%, ${Math.round(900 * prefs.zoom)}px)` }}>
            {displayPages.map((src, i) => (
              <div key={i} data-page={i} className="relative w-full" style={{ minHeight: '40vh' }}>
                <PageImage key={src} src={src} alt={`Página ${i + 1}`} className="w-full" eager={Math.abs(i - chapter.startPage) < 3} />
              </div>
            ))}
            <div ref={endRef} className="w-full"><EndPanel chapter={chapter} inline /></div>
          </div>
        </div>
      ) : (
        <div className="h-full overflow-auto" onClick={onZoneClick} onDoubleClick={onDoubleClick}>
          <div className="flex min-h-full min-w-full items-center justify-center" style={{ transform: `scale(${prefs.zoom})`, transformOrigin: 'top center' }}>
            {ended ? <EndPanel chapter={chapter} /> : ordered.map((i) => (
              <PageImage key={displayPages[i]} src={displayPages[i]} alt={`Página ${i + 1}`} eager
                className={cx(imgClass, prefs.mode === 'double' && prefs.fit !== 'original' && 'max-w-[50vw]', 'object-contain')} />
            ))}
          </div>
        </div>
      )}

      {/* Número da página discreto com a interface escondida */}
      {!ui && (
        <span className="pointer-events-none absolute bottom-2 left-1/2 z-10 -translate-x-1/2 rounded-full bg-black/60 px-2.5 py-0.5 text-[11px] tabular-nums text-zinc-200">
          {ended ? 'Fim' : `${Math.min(page + 1, total)} / ${total}`}
        </span>
      )}

      {/* Barra superior */}
      <div className={cx('absolute inset-x-0 top-0 z-10 flex items-center gap-3 bg-gradient-to-b from-black/90 to-black/0 px-3 pb-8 pt-3 transition-opacity duration-300', ui ? 'opacity-100' : 'pointer-events-none opacity-0')}>
        <Link to={`/conteudo/${chapter.contentId}`} className="btn-ghost p-2" title="Voltar (Esc)"><ArrowLeft className="h-5 w-5" /></Link>
        <div className="min-w-0 flex-1">
          <p className="truncate text-sm font-semibold">{chapter.contentTitle}</p>
          <p className="truncate text-xs text-zinc-400">{chapter.label}{chapter.title && chapter.title !== chapter.label ? ` · ${chapter.title}` : ''}{chapter.local ? ' · local' : ''}</p>
        </div>
        <span className="hidden text-sm tabular-nums text-zinc-300 sm:block">{Math.min(page + 1, total)} / {total}</span>
        {translationReady ? (
          <button className="btn-secondary px-2 py-1.5 text-xs" onClick={() => {
            const next = new URLSearchParams(params)
            if (translated) next.delete('traducao'); else next.set('traducao', 'pt-br')
            setParams(next, { replace: true })
          }}>{translated ? 'Português · ver original' : 'Original · ver português'}</button>
        ) : <TranslationControls chapterId={chapter.chapterId} label={chapter.label} />}
        {save.isPending && <Spinner className="h-4 w-4" />}
        <button className="btn-ghost p-2" onClick={() => setPrefs({ zoom: Math.max(0.5, +(prefs.zoom - 0.25).toFixed(2)) })} title="Diminuir zoom (-)"><ZoomOut className="h-5 w-5" /></button>
        <button className="btn-ghost p-2" onClick={() => setPrefs({ zoom: Math.min(3, +(prefs.zoom + 0.25).toFixed(2)) })} title="Aumentar zoom (+)"><ZoomIn className="h-5 w-5" /></button>
        <button className="btn-ghost p-2" onClick={toggleFullscreen} title="Tela cheia (F)">{fullscreen ? <Minimize className="h-5 w-5" /> : <Maximize className="h-5 w-5" />}</button>
        <div className="relative">
          <button className="btn-ghost p-2" onClick={() => setSettingsOpen((v) => !v)} title="Modo de leitura"><Settings2 className="h-5 w-5" /></button>
          {settingsOpen && (
            <div className="card absolute right-0 top-11 w-72 space-y-4 p-4 shadow-2xl animate-fade-in">
              <Option label="Modo">
                <Seg active={prefs.mode === 'vertical'} onClick={() => setPrefs({ mode: 'vertical', fit: 'width' })}><Rows3 className="h-4 w-4" /> Vertical</Seg>
                <Seg active={prefs.mode === 'single'} onClick={() => setPrefs({ mode: 'single' })}><Square className="h-4 w-4" /> Única</Seg>
                <Seg active={prefs.mode === 'double'} onClick={() => setPrefs({ mode: 'double', fit: 'height' })}><Columns2 className="h-4 w-4" /> Dupla</Seg>
              </Option>
              <Option label="Direção">
                <Seg active={!rtl} onClick={() => setPrefs({ direction: 'ltr' })}>Esq. → Dir.</Seg>
                <Seg active={rtl} onClick={() => setPrefs({ direction: 'rtl' })}>Dir. → Esq.</Seg>
              </Option>
              <Option label="Ajuste">
                <Seg active={prefs.fit === 'width'} onClick={() => setPrefs({ fit: 'width' })}><MoveHorizontal className="h-4 w-4" /> Largura</Seg>
                <Seg active={prefs.fit === 'height'} onClick={() => setPrefs({ fit: 'height' })}>Altura</Seg>
                <Seg active={prefs.fit === 'original'} onClick={() => setPrefs({ fit: 'original' })}>Original</Seg>
              </Option>
              <Option label="Fundo">
                {(Object.keys(BG) as Bg[]).map((b) => <Seg key={b} active={prefs.bg === b} onClick={() => setPrefs({ bg: b })}>{BG[b].label}</Seg>)}
              </Option>
              {prefs.mode === 'vertical' && (
                <Option label="Espaço entre páginas">
                  <Seg active={!prefs.gap} onClick={() => setPrefs({ gap: false })}>Sem espaço</Seg>
                  <Seg active={prefs.gap} onClick={() => setPrefs({ gap: true })}>Com espaço</Seg>
                </Option>
              )}
              <p className="text-[11px] leading-relaxed text-zinc-500">
                Teclado: ← → navegar · Espaço/PgDn avança uma tela · F tela cheia · +/− zoom · N/P próximo/anterior capítulo · Esc sair.
                Toque: {prefs.mode === 'vertical' ? 'em cima/embaixo rola a tela' : 'nas laterais vira a página'}, no centro mostra os controles; duplo toque aproxima.
              </p>
            </div>
          )}
        </div>
      </div>

      {translated && !translationReady && (
        <div className="absolute inset-x-3 top-24 z-10 rounded-lg bg-amber-950/95 p-3 text-sm text-amber-200" role="status">
          {translation.error ? 'Não foi possível consultar a tradução.' : 'A tradução ainda não está pronta.'} Exibindo o original.
        </div>
      )}

      {/* Barra inferior */}
      <div className={cx('absolute inset-x-0 bottom-0 z-10 flex items-center gap-3 bg-gradient-to-t from-black/90 to-black/0 px-3 pb-4 pt-10 transition-opacity duration-300', ui ? 'opacity-100' : 'pointer-events-none opacity-0')}>
        <button className="btn-secondary px-2.5" disabled={!chapter.prevChapterId} onClick={() => chapter.prevChapterId && navigate(`/ler/${chapter.prevChapterId}`)} title="Capítulo anterior (P)">
          <ChevronLeft className="h-4 w-4" /> <span className="hidden sm:inline">Anterior</span>
        </button>
        <input type="range" min={0} max={Math.max(0, total - 1)} value={page} dir={rtl && prefs.mode !== 'vertical' ? 'rtl' : 'ltr'}
          onChange={(e) => {
            const p = Number(e.target.value)
            setEnded(false)
            setPage(p)
            if (prefs.mode === 'vertical') verticalRef.current?.querySelector(`[data-page="${p}"]`)?.scrollIntoView({ block: 'start' })
          }}
          className="flex-1 accent-accent-500" />
        <span className="w-16 text-center text-xs tabular-nums text-zinc-300 sm:hidden">{page + 1}/{total}</span>
        <button className="btn-secondary px-2.5" disabled={!chapter.nextChapterId} onClick={() => chapter.nextChapterId && navigate(`/ler/${chapter.nextChapterId}`)} title="Próximo capítulo (N)">
          <span className="hidden sm:inline">Próximo</span> <ChevronRight className="h-4 w-4" />
        </button>
      </div>
    </div>
  )
}

function PageImage({ src, alt, className, eager }: { src: string; alt: string; className?: string; eager?: boolean }) {
  const [state, setState] = useState<'loading' | 'ok' | 'error'>('loading')
  const [attempt, setAttempt] = useState(0)
  return (
    <div className="relative flex items-center justify-center">
      {state === 'loading' && <div className="absolute inset-0 flex min-h-[40vh] items-center justify-center"><Spinner className="h-6 w-6" /></div>}
      {state === 'error' ? (
        <div className="flex min-h-[40vh] w-full flex-col items-center justify-center gap-2 text-sm text-zinc-400" onClick={(e) => e.stopPropagation()}>
          Falha ao carregar {alt.toLowerCase()}.
          <button className="btn-secondary" onClick={() => { setState('loading'); setAttempt((a) => a + 1) }}>Tentar novamente</button>
        </div>
      ) : (
        <img src={attempt ? `${src}?r=${attempt}` : src} alt={alt} draggable={false} loading={eager ? 'eager' : 'lazy'} decoding="async"
          onLoad={() => setState('ok')} onError={() => setState('error')} className={cx(className, state === 'loading' && 'min-h-[40vh] opacity-0')} />
      )}
    </div>
  )
}

function EndPanel({ chapter, inline }: { chapter: ReaderChapter; inline?: boolean }) {
  return (
    <div className={cx('flex flex-col items-center gap-4 p-10 text-center', inline ? 'w-full' : 'card m-4 max-w-md')} onClick={(e) => e.stopPropagation()}>
      <p className="text-lg font-semibold">Fim de {chapter.label}</p>
      <p className="text-sm text-zinc-400">Progresso salvo automaticamente.</p>
      <div className="flex flex-wrap justify-center gap-2">
        {chapter.nextChapterId ? (
          <Link to={`/ler/${chapter.nextChapterId}`} className="btn-primary">Próximo capítulo <ChevronRight className="h-4 w-4" /></Link>
        ) : <span className="text-sm text-zinc-500">Este é o último capítulo disponível.</span>}
        <Link to={`/conteudo/${chapter.contentId}`} className="btn-secondary">Voltar ao título</Link>
      </div>
    </div>
  )
}

function Option({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <p className="text-xs font-medium uppercase tracking-wide text-zinc-500">{label}</p>
      <div className="flex gap-1 rounded-lg bg-ink-900 p-1">{children}</div>
    </div>
  )
}

function Seg({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button onClick={onClick} className={cx('flex flex-1 items-center justify-center gap-1 rounded-md px-2 py-1.5 text-xs transition-colors', active ? 'bg-accent-600 text-white' : 'text-zinc-400 hover:text-white')}>
      {children}
    </button>
  )
}
