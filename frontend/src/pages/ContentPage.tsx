import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  BookOpen, Check, CheckCheck, Download, ExternalLink, Eye, EyeOff, HardDrive, Heart, Link2, Loader2, Play, Plus,
  Layers, Radar, RefreshCw, Search, Sparkles, Trash2, Unlink,
} from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { Category, ChapterView, CheckResult, ContentView, EnqueueResult, LibraryStatus, SearchResponse, Settings } from '../api/types'
import { ChapterControls, matchesChapter, useChapterControls } from '../components/ChapterControls'
import { Modal } from '../components/Modal'
import { useToast } from '../components/Toast'
import { Badge, Cover, EmptyState, ErrorState, PageLoader, Spinner, Toggle } from '../components/ui'
import { groupByVolume } from '../lib/chapters'
import { loadSourcePreference, saveSourcePreference } from '../lib/sourcePreference'
import { cx, downloadStatusLabel, formatBytes, formatDate, languageLabel, publicationLabel, statusLabel, timeAgo, typeLabel } from '../lib/format'
import { Hero } from './RemoteTitlePage'

const STATUSES: LibraryStatus[] = ['READING', 'PLAN_TO_READ', 'COMPLETED', 'PAUSED', 'DROPPED']

export default function ContentPage() {
  const { contentId = '' } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const toast = useToast()
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [lang, setLang] = useState('all')
  const [source, setSource] = useState(() => loadSourcePreference(contentId))
  useEffect(() => setSource(loadSourcePreference(contentId)), [contentId])
  const [onlyUnread, setOnlyUnread] = useState(false)
  const [onlyDownloaded, setOnlyDownloaded] = useState(false)
  const [linkOpen, setLinkOpen] = useState(false)
  const [bestOnly, setBestOnly] = useState(true)
  const controls = useChapterControls()
  const [expanded, setExpanded] = useState<Set<string>>(new Set())

  const key = ['content', contentId]
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: key,
    queryFn: async () => (await api.get<ContentView>(`/contents/${contentId}`)).data,
    refetchInterval: (q) => q.state.data?.chapters.some((c) => c.downloadStatus === 'QUEUED' || c.downloadStatus === 'DOWNLOADING') ? 3000 : false,
  })
  const { data: categories } = useQuery({ queryKey: ['categories'], queryFn: async () => (await api.get<Category[]>('/library/categories')).data })
  const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: async () => (await api.get<Settings>('/settings')).data })
  const { data: next } = useQuery({
    queryKey: ['continue', contentId],
    queryFn: async () => (await api.get<{ chapterId: string; page: number; label: string } | ''>(`/contents/${contentId}/continue`)).data || null,
  })

  const invalidate = () => {
    qc.invalidateQueries({ queryKey: key })
    qc.invalidateQueries({ queryKey: ['library'] })
    qc.invalidateQueries({ queryKey: ['continue', contentId] })
  }
  const onError = (e: unknown) => toast.error(errorMessage(e))

  const updateEntry = useMutation({
    mutationFn: (patch: Record<string, unknown>) => api.patch(`/library/${data!.library!.entryId}`, patch),
    onSuccess: invalidate, onError,
  })
  const addToLibrary = useMutation({
    mutationFn: () => api.post('/library', { contentId }),
    onSuccess: () => { toast.success('Adicionado à biblioteca.'); invalidate() }, onError,
  })
  const removeFromLibrary = useMutation({
    mutationFn: () => api.delete(`/library/${data!.library!.entryId}`),
    onSuccess: () => { toast.success('Removido da biblioteca. Arquivos baixados foram mantidos.'); qc.invalidateQueries({ queryKey: ['library'] }); navigate('/biblioteca') }, onError,
  })
  const refresh = useMutation({
    mutationFn: async () => (await api.post<{ newChapters: number; errors: string[] }>(`/contents/${contentId}/refresh`)).data,
    onSuccess: (r) => { r.errors.length ? toast.error(r.errors.join('; ')) : toast.success(`${r.newChapters} novo(s) capítulo(s).`); invalidate() }, onError,
  })
  const check = useMutation({
    mutationFn: async () => (await api.post<CheckResult>(`/library/${data!.library!.entryId}/check`)).data,
    onSuccess: (r) => { r.errors.length ? toast.error(r.errors.join('; ')) : toast.success(`Verificação concluída: ${r.newChapters} novo(s), ${r.autoQueued} na fila.`); invalidate() }, onError,
  })
  const enqueue = useMutation({
    mutationFn: async (ids: string[]) => (await api.post<EnqueueResult>('/downloads', { chapterIds: ids })).data,
    onSuccess: (r) => { reportEnqueue(r); setSelected(new Set()); invalidate() }, onError,
  })
  const enqueueContent = useMutation({
    mutationFn: async (volume: string | null) => (await api.post<EnqueueResult>(`/downloads/content/${contentId}`, { volume, onlyMissing: true })).data,
    onSuccess: (r) => { reportEnqueue(r); invalidate() }, onError,
  })
  const markRead = useMutation({
    mutationFn: (p: { ids: string[]; read: boolean }) => api.post(`/contents/${contentId}/chapters/read`, { chapterIds: p.ids, read: p.read }),
    onSuccess: () => { setSelected(new Set()); invalidate() }, onError,
  })
  const findSources = useMutation({
    mutationFn: async () => (await api.post<{ linked: string[]; newChapters: number; skipped: string[]; errors: string[] }>(`/contents/${contentId}/find-sources`)).data,
    onSuccess: (r) => {
      if (r.linked.length) toast.success(`Fontes vinculadas: ${r.linked.join(', ')}. ${r.newChapters} capítulo(s) novo(s).`)
      else if (r.skipped.length) toast.info(`${r.skipped.join(', ')} têm este título, mas sem capítulos novos. Instale outras extensões pt-BR em Fontes e extensões.`)
      else toast.info('Nenhuma outra fonte habilitada tem este título. Instale mais extensões em Fontes e extensões.')
      if (r.errors.length) toast.error(r.errors.join('; '))
      invalidate()
    },
    onError,
  })
  const unlink = useMutation({
    mutationFn: (sourceId: string) => api.delete(`/contents/${contentId}/sources/${sourceId}`),
    onSuccess: invalidate, onError,
  })

  const reportEnqueue = (r: EnqueueResult) => {
    const reasons = Array.from(new Set(r.skipped.map((s) => s.reason)))
    toast.success(`${r.queued} capítulo(s) na fila de downloads.${reasons.length ? ` Ignorados: ${reasons.join(', ')}.` : ''}`)
  }

  const activeSource = data?.sources.some((s) => s.providerCode === source) ? source : 'all'
  const chapters = useMemo(() => (data?.chapters ?? []).filter((c) =>
    (activeSource === 'all' || c.providerCode === activeSource) &&
    (lang === 'all' || c.language === lang || (lang === 'local' && c.providerCode === 'local')) &&
    (!onlyUnread || !c.read) && (!onlyDownloaded || c.downloaded) && matchesChapter(c, controls.query)),
  [data, activeSource, lang, onlyUnread, onlyDownloaded, controls.query])
  const preferred = settings?.languages[0] ?? 'pt-br'
  /** Uma linha por número de capítulo: escolhe a melhor versão e guarda as alternativas. */
  const rows = useMemo(() => {
    if (!bestOnly) return chapters.map((c) => ({ chapter: c, alternatives: [] as ChapterView[] }))
    const score = (c: ChapterView) => (c.downloaded ? 8 : 0) + (c.readable ? 4 : 0) + (c.language === preferred ? 2 : 0) + (c.read || c.progressPage ? 1 : 0)
    const groups = new Map<string, ChapterView[]>()
    for (const c of chapters) {
      const key = c.number != null ? `n${c.number}` : `id${c.id}`
      groups.set(key, [...(groups.get(key) ?? []), c])
    }
    return Array.from(groups.values()).map((list) => {
      const sorted = [...list].sort((a, b) => score(b) - score(a))
      return { chapter: sorted[0], alternatives: sorted.slice(1) }
    })
  }, [chapters, bestOnly, preferred])
  const languages = useMemo(() => Array.from(new Set((data?.chapters ?? []).map((c) => c.language).filter(Boolean))) as string[], [data])
  const volumes = useMemo(() => Array.from(new Set((data?.chapters ?? []).filter((c) => c.downloadable && c.volume).map((c) => c.volume!))), [data])
  const resumeChapter = data?.chapters.find((c) => c.id === next?.chapterId)
  const sourceResume = activeSource !== 'all' && resumeChapter?.providerCode !== activeSource
    ? chapters.find((c) => c.readable && c.number != null && c.number === resumeChapter?.number)
      ?? chapters.find((c) => c.readable && !c.read) ?? chapters.find((c) => c.readable)
    : undefined
  const readingTarget = sourceResume
    ? { chapterId: sourceResume.id, page: sourceResume.progressPage ?? 0, label: sourceResume.label }
    : activeSource === 'all' || resumeChapter?.providerCode === activeSource ? next : null

  if (isLoading) return <PageLoader />
  if (error || !data) return <ErrorState message={errorMessage(error)} onRetry={refetch} />
  const comic = data.type === 'COMIC'
  const lib = data.library
  const toggleSel = (id: string) => setSelected((s) => { const n = new Set(s); if (n.has(id)) n.delete(id); else n.add(id); return n })
  const anyDownloadable = data.chapters.some((c) => c.downloadable && !c.downloaded)

  return (
    <div className="space-y-8">
      <Hero coverUrl={data.coverUrl}>
        <Cover src={data.coverUrl} alt={data.title} className="h-72 w-48 shrink-0 rounded-xl shadow-2xl ring-1 ring-white/10" />
        <div className="flex min-w-0 flex-1 flex-col gap-3">
          <div className="flex flex-wrap gap-1.5">
            <Badge tone={comic ? 'blue' : 'accent'}>{typeLabel[data.type]}</Badge>
            {data.status && <Badge>{publicationLabel[data.status] ?? data.status}</Badge>}
            {data.year && <Badge>{data.year}</Badge>}
            {data.local && <Badge tone="green"><HardDrive className="h-3 w-3" /> Local</Badge>}
            {lib?.monitored && <Badge tone="green"><Radar className="h-3 w-3" /> Monitorado</Badge>}
          </div>
          <h1 className="text-3xl font-bold leading-tight text-white">{data.title}</h1>
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            {data.authors.length > 0 && <><dt className="text-zinc-500">Autor</dt><dd>{data.authors.join(', ')}</dd></>}
            {data.artists.length > 0 && <><dt className="text-zinc-500">Arte</dt><dd>{data.artists.join(', ')}</dd></>}
            {data.publisher && <><dt className="text-zinc-500">Editora</dt><dd>{data.publisher}</dd></>}
            <dt className="text-zinc-500">Progresso</dt><dd>{data.readChapters}/{data.totalChapters} lidos · {data.downloadedChapters} no disco</dd>
            {lib?.lastCheckedAt && <><dt className="text-zinc-500">Verificado</dt><dd>{timeAgo(lib.lastCheckedAt)}</dd></>}
          </dl>
          <div className="flex flex-wrap gap-2 pt-1">
            {readingTarget && <Link to={`/ler/${readingTarget.chapterId}`} className="btn-primary"><Play className="h-4 w-4 fill-current" /> {data.readChapters > 0 || readingTarget.page > 0 ? 'Continuar' : 'Começar'}: {readingTarget.label}</Link>}
            {!lib && <button className="btn-primary" onClick={() => addToLibrary.mutate()}><Plus className="h-4 w-4" /> Adicionar à biblioteca</button>}
            {lib && (
              <button className={cx('btn-secondary', lib.favorite && 'text-pink-accent')} onClick={() => updateEntry.mutate({ favorite: !lib.favorite })}>
                <Heart className={cx('h-4 w-4', lib.favorite && 'fill-current')} /> {lib.favorite ? 'Favorito' : 'Favoritar'}
              </button>
            )}
            <button className="btn-secondary" disabled={refresh.isPending} onClick={() => refresh.mutate()}>
              <RefreshCw className={cx('h-4 w-4', refresh.isPending && 'animate-spin')} /> Atualizar
            </button>
            {anyDownloadable && (
              <button className="btn-secondary" disabled={enqueueContent.isPending} onClick={() => enqueueContent.mutate(null)}><Download className="h-4 w-4" /> Baixar faltantes</button>
            )}
          </div>
        </div>
      </Hero>

      {lib && (
        <div className="card grid gap-4 p-4 sm:grid-cols-2 xl:grid-cols-4">
          <label className="space-y-1.5 text-sm">
            <span className="text-zinc-400">Status de leitura</span>
            <select className="input" value={lib.status} onChange={(e) => updateEntry.mutate({ status: e.target.value })}>
              {STATUSES.map((s) => <option key={s} value={s}>{statusLabel[s]}</option>)}
            </select>
          </label>
          <label className="space-y-1.5 text-sm">
            <span className="text-zinc-400">Categoria</span>
            <select className="input" value={lib.categoryId ?? ''} onChange={(e) => updateEntry.mutate(e.target.value ? { categoryId: e.target.value } : { clearCategory: true })}>
              <option value="">Sem categoria</option>
              {categories?.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
            </select>
          </label>
          <div className="space-y-2 text-sm">
            <span className="text-zinc-400">Monitoramento</span>
            <div className="flex flex-col gap-2">
              <Toggle checked={lib.monitored} onChange={(v) => updateEntry.mutate({ monitored: v })} label="Monitorar novas edições" />
              <Toggle checked={lib.autoDownload} disabled={!lib.monitored} onChange={(v) => updateEntry.mutate({ autoDownload: v })} label="Baixar automaticamente" />
            </div>
          </div>
          <div className="flex flex-col justify-end gap-2">
            <button className="btn-secondary" disabled={check.isPending || !data.sources.length} onClick={() => check.mutate()}>
              {check.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Radar className="h-4 w-4" />} Verificar agora
            </button>
            <button className="btn-ghost text-red-300" onClick={() => confirm('Remover da biblioteca? Os arquivos no disco serão mantidos.') && removeFromLibrary.mutate()}>
              <Trash2 className="h-4 w-4" /> Remover da biblioteca
            </button>
          </div>
        </div>
      )}

      {data.description && <p className="max-w-4xl whitespace-pre-line text-sm leading-relaxed text-zinc-300">{data.description}</p>}
      {data.genres.length > 0 && <div className="flex flex-wrap gap-1.5">{data.genres.map((g) => <Badge key={g}>{g}</Badge>)}</div>}
      {data.characters.length > 0 && <p className="text-sm text-zinc-400"><span className="text-zinc-500">Personagens: </span>{data.characters.slice(0, 25).join(', ')}</p>}

      <section className="space-y-3">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold">Fontes disponíveis</h2>
          <div className="flex flex-wrap gap-2">
            <button className="btn-secondary" disabled={findSources.isPending} onClick={() => findSources.mutate()}>
              {findSources.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />} Encontrar mais fontes
            </button>
            <button className="btn-secondary" onClick={() => setLinkOpen(true)}><Link2 className="h-4 w-4" /> Vincular manualmente {comic && '/ Comic Vine'}</button>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          {data.local && <div className="card flex items-center gap-2 px-3 py-2 text-sm"><HardDrive className="h-4 w-4 text-emerald-400" /> Arquivos locais <span className="max-w-xs truncate font-mono text-xs text-zinc-500">{data.localPath}</span></div>}
          {data.sources.map((s) => (
            <div key={s.id} className="card flex items-center gap-2 px-3 py-2 text-sm">
              <span className="font-medium">{s.providerName}</span>
              {s.language && <Badge>{s.language}</Badge>}
              {s.capabilities?.metadataOnly && <Badge tone="amber">Metadados</Badge>}
              {s.capabilities?.download && <Badge tone="green">Download</Badge>}
              <span className="text-xs text-zinc-500">sinc. {timeAgo(s.lastSyncedAt)}</span>
              {s.url && <a href={s.url} target="_blank" rel="noreferrer noopener" className="text-zinc-400 hover:text-white"><ExternalLink className="h-3.5 w-3.5" /></a>}
              <button className="text-zinc-500 hover:text-red-300" title="Desvincular" onClick={() => confirm(`Desvincular ${s.providerName}?`) && unlink.mutate(s.id)}><Unlink className="h-3.5 w-3.5" /></button>
            </div>
          ))}
          {!data.local && data.sources.length === 0 && <p className="text-sm text-zinc-500">Nenhuma fonte vinculada.</p>}
        </div>
      </section>

      {data.officialOnlyCount > 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-xl border border-amber-500/30 bg-amber-500/10 p-4 text-sm">
          <ExternalLink className="h-5 w-5 shrink-0 text-amber-300" />
          <p className="flex-1 text-amber-100">
            {data.officialOnlyCount} {comic ? 'edição(ões)' : 'capítulo(s)'} só estão disponíveis no site oficial da editora (ex.: MANGA Plus),
            sem páginas hospedadas nas fontes vinculadas. Outras fontes instaladas podem ter esses capítulos.
          </p>
          <button className="btn-secondary" disabled={findSources.isPending} onClick={() => findSources.mutate()}>
            {findSources.isPending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />} Procurar em outras fontes
          </button>
        </div>
      )}

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">{comic ? 'Edições' : 'Capítulos'} <span className="text-sm font-normal text-zinc-500">({rows.length})</span></h2>
          <div className="flex flex-wrap items-center gap-1.5">
            {data.sources.length > 1 && (
              <label className="flex items-center gap-2 text-xs text-zinc-400">Fonte
                <select className="input w-auto py-1 text-xs" value={activeSource} onChange={(e) => { setSource(e.target.value); saveSourcePreference(contentId, e.target.value); setSelected(new Set()) }}>
                  <option value="all">Todas as fontes</option>
                  {data.sources.map((s) => <option key={s.id} value={s.providerCode}>{s.providerName}</option>)}
                </select>
              </label>
            )}
            <button className={cx('chip', bestOnly && 'chip-active')} onClick={() => setBestOnly(!bestOnly)} title="Mostra só a melhor versão de cada número"><Layers className="h-3 w-3" /> Uma versão por capítulo</button>
            {languages.length > 1 && <>
              <button className={cx('chip', lang === 'all' && 'chip-active')} onClick={() => setLang('all')}>Todos idiomas</button>
              {languages.map((l) => <button key={l} className={cx('chip', lang === l && 'chip-active')} onClick={() => setLang(l)}>{languageLabel(l)}</button>)}
            </>}
            <button className={cx('chip', onlyUnread && 'chip-active')} onClick={() => setOnlyUnread(!onlyUnread)}><EyeOff className="h-3 w-3" /> Não lidos</button>
            <button className={cx('chip', onlyDownloaded && 'chip-active')} onClick={() => setOnlyDownloaded(!onlyDownloaded)}><HardDrive className="h-3 w-3" /> Baixados</button>
            {volumes.length > 0 && (
              <select className="input w-auto py-1 text-xs" value="" onChange={(e) => e.target.value && enqueueContent.mutate(e.target.value)}>
                <option value="">Baixar volume…</option>
                {volumes.map((v) => <option key={v} value={v}>Volume {v}</option>)}
              </select>
            )}
          </div>
        </div>

        <ChapterControls {...controls} comic={comic} />

        {selected.size > 0 && (
          <div className="sticky top-16 z-10 flex flex-wrap items-center gap-2 rounded-xl border border-accent-500/40 bg-ink-800/95 px-4 py-2.5 shadow-xl backdrop-blur animate-fade-in">
            <span className="text-sm">{selected.size} selecionado(s)</span>
            <button className="btn-primary py-1.5" onClick={() => enqueue.mutate([...selected])}><Download className="h-4 w-4" /> Baixar</button>
            <button className="btn-secondary py-1.5" onClick={() => markRead.mutate({ ids: [...selected], read: true })}><Eye className="h-4 w-4" /> Marcar lido</button>
            <button className="btn-secondary py-1.5" onClick={() => markRead.mutate({ ids: [...selected], read: false })}><EyeOff className="h-4 w-4" /> Marcar não lido</button>
            <button className="btn-ghost ml-auto py-1.5" onClick={() => setSelected(new Set())}>Limpar</button>
          </div>
        )}

        {chapters.length === 0 && (controls.query
          ? <EmptyState title="Nenhum capítulo encontrado" description={`Nada corresponde a "${controls.query}" nas fontes vinculadas.`} />
          : <EmptyState title="Nenhum capítulo" description="Atualize as fontes ou ajuste os filtros." />)}
        {groupByVolume(rows.flatMap((r) => [r.chapter, ...(expanded.has(r.chapter.id) ? r.alternatives : [])]), controls.desc).map((g) => (
          <div key={g.volume ?? 'none'} className="card overflow-hidden">
            <div className="flex items-center gap-3 border-b border-ink-700 bg-ink-800/60 px-4 py-2">
              <input type="checkbox" className="accent-accent-500" checked={g.items.every((c) => selected.has(c.id))}
                onChange={(e) => setSelected((s) => { const n = new Set(s); g.items.forEach((c) => e.target.checked ? n.add(c.id) : n.delete(c.id)); return n })} />
              <span className="text-xs font-semibold uppercase tracking-wide text-zinc-400">{g.volume ? `Volume ${g.volume}` : 'Sem volume'}</span>
            </div>
            <ul className="divide-y divide-ink-700">
              {g.items.map((c) => (
                <li key={c.id} className={cx('flex items-center gap-3 px-4 py-2.5 text-sm', c.read && 'opacity-60')}>
                  <input type="checkbox" className="accent-accent-500" checked={selected.has(c.id)} onChange={() => toggleSel(c.id)} />
                  <div className="min-w-0 flex-1">
                    <p className="flex items-center gap-2 truncate text-zinc-100">
                      {c.read && <CheckCheck className="h-4 w-4 shrink-0 text-emerald-400" />}
                      <span className="truncate">{c.label}{c.title && c.title !== c.label ? ` · ${c.title}` : ''}</span>
                    </p>
                    <p className="text-xs text-zinc-500">
                      {[c.providerCode === 'local' ? 'Local' : languageLabel(c.language), c.scanlator, formatDate(c.publishedAt), c.pageCount ? `${c.pageCount} pág.` : null,
                        c.fileSize ? formatBytes(c.fileSize) : null, !c.read && c.progressPage ? `parou na pág. ${c.progressPage + 1}` : null].filter(Boolean).join(' · ')}
                    </p>
                  </div>
                  {(() => {
                    const alts = rows.find((r) => r.chapter.id === c.id)?.alternatives.length ?? 0
                    return alts > 0 ? (
                      <button className="chip px-2 py-0.5" onClick={() => setExpanded((s) => { const n = new Set(s); if (n.has(c.id)) n.delete(c.id); else n.add(c.id); return n })}>
                        {expanded.has(c.id) ? 'ocultar' : `+${alts} ${alts === 1 ? 'versão' : 'versões'}`}
                      </button>
                    ) : null
                  })()}
                  {c.downloaded && <Badge tone="green"><HardDrive className="h-3 w-3" /> No disco</Badge>}
                  {c.downloadStatus && <Badge tone={c.downloadStatus === 'FAILED' ? 'red' : 'accent'}>{c.downloadStatus === 'DOWNLOADING' && <Spinner className="h-3 w-3" />}{downloadStatusLabel[c.downloadStatus]}</Badge>}
                  {c.downloadable && !c.downloaded && !c.downloadStatus && (
                    <button className="btn-ghost p-1.5" title="Baixar" onClick={() => enqueue.mutate([c.id])}><Download className="h-4 w-4" /></button>
                  )}
                  {c.readable ? (
                    <Link to={`/ler/${c.id}`} className="btn-secondary px-2.5 py-1.5"><BookOpen className="h-4 w-4" /> Ler</Link>
                  ) : c.externalUrl ? (
                    <a href={c.externalUrl} target="_blank" rel="noreferrer noopener" className="btn-ghost px-2.5 py-1.5 text-amber-300" title="Capítulo oficial, disponível apenas no site da editora"><ExternalLink className="h-4 w-4" /> Site oficial</a>
                  ) : <Badge>Metadados</Badge>}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </section>

      <LinkSourceModal open={linkOpen} onClose={() => setLinkOpen(false)} content={data} onLinked={invalidate} />
    </div>
  )
}

function LinkSourceModal({ open, onClose, content, onLinked }: { open: boolean; onClose: () => void; content: ContentView; onLinked: () => void }) {
  const [q, setQ] = useState(content.title)
  const [submitted, setSubmitted] = useState<string | null>(null)
  const toast = useToast()
  const { data, isFetching, error } = useQuery({
    queryKey: ['link-search', submitted, content.type],
    queryFn: async () => (await api.get<SearchResponse>('/search', { params: { q: submitted, types: content.type } })).data,
    enabled: open && !!submitted,
  })
  const link = useMutation({
    mutationFn: (p: { providerCode: string; externalId: string }) => api.post(`/contents/${content.id}/sources`, p),
    onSuccess: () => { toast.success('Fonte vinculada.'); onLinked(); onClose() },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const linked = new Set(content.sources.map((s) => s.providerCode + '|' + s.externalId))
  return (
    <Modal open={open} onClose={onClose} title="Vincular fonte ou metadados" wide>
      <form className="mb-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); setSubmitted(q) }}>
        <input className="input" value={q} onChange={(e) => setQ(e.target.value)} />
        <button className="btn-primary shrink-0"><Search className="h-4 w-4" /> Buscar</button>
      </form>
      <p className="mb-3 text-xs text-zinc-500">Vincular uma fonte permite acompanhar novos capítulos/edições. Para HQs, o Comic Vine fornece editora, sinopse, personagens e capas (requer COMICVINE_API_KEY).</p>
      {isFetching && <div className="flex justify-center p-6"><Spinner /></div>}
      {error && <p className="text-sm text-red-300">{errorMessage(error)}</p>}
      <ul className="space-y-2">
        {data?.results.flatMap((r) => r.sources.map((s) => ({ r, s }))).map(({ r, s }) => (
          <li key={s.providerCode + s.externalId} className="flex items-center gap-3 rounded-lg bg-ink-800 p-2">
            <Cover src={s.coverUrl ?? r.coverUrl} alt={s.title} className="h-16 w-11 shrink-0 rounded" />
            <div className="min-w-0 flex-1">
              <p className="truncate text-sm font-medium">{s.title}</p>
              <p className="text-xs text-zinc-500">{s.providerName}{r.year ? ` · ${r.year}` : ''}{r.publisher ? ` · ${r.publisher}` : ''}</p>
            </div>
            {linked.has(s.providerCode + '|' + s.externalId) ? <Badge tone="green"><Check className="h-3 w-3" /> Vinculado</Badge> : (
              <button className="btn-secondary py-1.5" disabled={link.isPending || s.inLibrary} title={s.inLibrary ? 'Já vinculado a outro título' : ''}
                onClick={() => link.mutate({ providerCode: s.providerCode, externalId: s.externalId })}>Vincular</button>
            )}
          </li>
        ))}
      </ul>
      {data && data.providers.filter((p) => p.status !== 'OK').map((p) => <p key={p.code} className="mt-2 text-xs text-amber-300">{p.name}: {p.message}</p>)}
    </Modal>
  )
}
