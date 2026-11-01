import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpen, ExternalLink, Library, Plus } from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { ContentView, RemoteChapter, RemoteTitleView } from '../api/types'
import { useToast } from '../components/Toast'
import { Badge, Cover, EmptyState, ErrorState, PageLoader, Spinner } from '../components/ui'
import { groupByVolume } from '../lib/chapters'
import { cx, formatDate, languageLabel, publicationLabel, typeLabel } from '../lib/format'

export default function RemoteTitlePage() {
  const { providerCode = '', externalId = '' } = useParams()
  const navigate = useNavigate()
  const toast = useToast()
  const qc = useQueryClient()
  const [lang, setLang] = useState<string>('all')

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['title', providerCode, externalId],
    queryFn: async () => (await api.get<RemoteTitleView>(`/titles/${encodeURIComponent(providerCode)}/${encodeURIComponent(externalId)}`)).data,
  })

  const add = useMutation({
    mutationFn: async (opts: { monitored?: boolean; status?: string }) =>
      (await api.post<{ contentId: string }>('/library', { providerCode, externalId, ...opts })).data,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['library'] })
      qc.invalidateQueries({ queryKey: ['title', providerCode, externalId] })
    },
  })

  /** Ler exige o título no banco (para salvar o progresso): adiciona e abre o capítulo correspondente. */
  const read = useMutation({
    mutationFn: async (chapter: RemoteChapter) => {
      const { contentId } = await add.mutateAsync({ status: 'READING' })
      const content = (await api.get<ContentView>(`/contents/${contentId}`)).data
      const match = content.chapters.find((c) => c.providerCode === providerCode && c.externalId === chapter.externalId)
      if (!match) throw new Error('Capítulo não encontrado após importar o título.')
      return match.id
    },
    onSuccess: (chapterId) => navigate(`/ler/${chapterId}`),
    onError: (e) => toast.error(errorMessage(e)),
  })

  const chapters = useMemo(() => (data?.chapters ?? []).filter((c) => lang === 'all' || c.language === lang), [data, lang])
  const languages = useMemo(() => Array.from(new Set((data?.chapters ?? []).map((c) => c.language).filter(Boolean))) as string[], [data])

  if (isLoading) return <PageLoader />
  if (error || !data) return <ErrorState message={errorMessage(error)} onRetry={refetch} />
  const d = data.details

  return (
    <div className="space-y-8">
      <Hero coverUrl={d.coverUrl}>
        <Cover src={d.coverUrl} alt={d.title} className="h-72 w-48 shrink-0 rounded-xl shadow-2xl ring-1 ring-white/10" />
        <div className="flex min-w-0 flex-col gap-3">
          <div className="flex flex-wrap gap-1.5">
            {d.type && <Badge tone={d.type === 'COMIC' ? 'blue' : 'accent'}>{typeLabel[d.type]}</Badge>}
            {d.status && <Badge>{publicationLabel[d.status] ?? d.status}</Badge>}
            {d.year && <Badge>{d.year}</Badge>}
            <Badge>{data.providerName}</Badge>
            {data.capabilities.metadataOnly && <Badge tone="amber">Somente metadados</Badge>}
          </div>
          <h1 className="text-3xl font-bold leading-tight text-white">{d.title}</h1>
          {d.altTitles.length > 0 && <p className="text-sm text-zinc-400">{d.altTitles.slice(0, 4).join(' · ')}</p>}
          <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1 text-sm">
            {d.authors.length > 0 && <><dt className="text-zinc-500">Autor</dt><dd>{d.authors.join(', ')}</dd></>}
            {d.artists.length > 0 && <><dt className="text-zinc-500">Arte</dt><dd>{d.artists.join(', ')}</dd></>}
            {d.publisher && <><dt className="text-zinc-500">Editora</dt><dd>{d.publisher}</dd></>}
            {d.languages.length > 0 && <><dt className="text-zinc-500">Idiomas</dt><dd className="line-clamp-2">{d.languages.map(languageLabel).join(', ')}</dd></>}
          </dl>
          <div className="flex flex-wrap gap-2 pt-1">
            {data.libraryContentId ? (
              <Link to={`/conteudo/${data.libraryContentId}`} className="btn-primary"><Library className="h-4 w-4" /> Abrir na biblioteca</Link>
            ) : (
              <>
                <button className="btn-primary" disabled={add.isPending} onClick={() => add.mutate({}, {
                  onSuccess: (r) => { toast.success('Adicionado à biblioteca.'); navigate(`/conteudo/${r.contentId}`) },
                  onError: (e) => toast.error(errorMessage(e)),
                })}>
                  {add.isPending ? <Spinner className="h-4 w-4 text-white" /> : <Plus className="h-4 w-4" />} Adicionar à biblioteca
                </button>
                <button className="btn-secondary" disabled={add.isPending} onClick={() => add.mutate({ monitored: true }, {
                  onSuccess: (r) => { toast.success('Adicionado e monitorando novas edições.'); navigate(`/conteudo/${r.contentId}`) },
                  onError: (e) => toast.error(errorMessage(e)),
                })}>Adicionar e monitorar</button>
              </>
            )}
            {d.url && <a href={d.url} target="_blank" rel="noreferrer noopener" className="btn-ghost"><ExternalLink className="h-4 w-4" /> Página original</a>}
          </div>
        </div>
      </Hero>

      {d.description && <p className="max-w-4xl whitespace-pre-line text-sm leading-relaxed text-zinc-300">{d.description}</p>}
      {d.genres.length > 0 && <div className="flex flex-wrap gap-1.5">{d.genres.map((g) => <Badge key={g}>{g}</Badge>)}</div>}
      {d.characters.length > 0 && <p className="text-sm text-zinc-400"><span className="text-zinc-500">Personagens: </span>{d.characters.slice(0, 20).join(', ')}</p>}

      <section className="space-y-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">{d.type === 'COMIC' ? 'Edições' : 'Capítulos'} <span className="text-sm font-normal text-zinc-500">({chapters.length})</span></h2>
          {languages.length > 1 && (
            <div className="flex flex-wrap gap-1.5">
              <button className={cx('chip', lang === 'all' && 'chip-active')} onClick={() => setLang('all')}>Todos</button>
              {languages.map((l) => <button key={l} className={cx('chip', lang === l && 'chip-active')} onClick={() => setLang(l)}>{languageLabel(l)}</button>)}
            </div>
          )}
        </div>
        {data.chaptersError && <ErrorState message={`Não foi possível carregar os capítulos: ${data.chaptersError}`} onRetry={refetch} />}
        {!data.chaptersError && chapters.length === 0 && <EmptyState title="Nenhum capítulo disponível" description="A fonte não possui capítulos nos idiomas configurados." />}
        {groupByVolume(chapters).map((g) => (
          <div key={g.volume ?? 'none'} className="card overflow-hidden">
            <div className="border-b border-ink-700 bg-ink-800/60 px-4 py-2 text-xs font-semibold uppercase tracking-wide text-zinc-400">{g.volume ? `Volume ${g.volume}` : 'Sem volume'}</div>
            <ul className="divide-y divide-ink-700">
              {g.items.map((c) => (
                <li key={c.externalId} className="flex items-center gap-3 px-4 py-2.5 text-sm">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-zinc-100">{d.type === 'COMIC' ? 'Edição' : 'Capítulo'} {c.numberLabel ?? '—'}{c.title ? ` · ${c.title}` : ''}</p>
                    <p className="text-xs text-zinc-500">{[languageLabel(c.language), c.scanlator, formatDate(c.publishedAt), c.pageCount ? `${c.pageCount} pág.` : null].filter(Boolean).join(' · ')}</p>
                  </div>
                  {c.readable ? (
                    <button className="btn-secondary px-2.5 py-1.5" disabled={read.isPending} onClick={() => read.mutate(c)}><BookOpen className="h-4 w-4" /> Ler</button>
                  ) : c.externalUrl ? (
                    <a className="btn-ghost px-2.5 py-1.5" href={c.externalUrl} target="_blank" rel="noreferrer noopener"><ExternalLink className="h-4 w-4" /> Site oficial</a>
                  ) : <Badge>Metadados</Badge>}
                </li>
              ))}
            </ul>
          </div>
        ))}
      </section>
    </div>
  )
}

export function Hero({ coverUrl, children }: { coverUrl: string | null; children: React.ReactNode }) {
  const bg = coverUrl ? (coverUrl.startsWith('/api/') ? coverUrl : `/api/images?src=${encodeURIComponent(coverUrl)}`) : null
  return (
    <div className="relative -mx-4 -mt-6 overflow-hidden px-4 pb-8 pt-8 sm:-mx-6 sm:px-6 lg:-mx-8 lg:px-8">
      {bg && <div className="absolute inset-0 scale-110 bg-cover bg-center opacity-25 blur-2xl" style={{ backgroundImage: `url("${bg}")` }} />}
      <div className="absolute inset-0 bg-gradient-to-b from-ink-900/40 via-ink-900/80 to-ink-900" />
      <div className="relative flex flex-col gap-6 sm:flex-row sm:items-end">{children}</div>
    </div>
  )
}
