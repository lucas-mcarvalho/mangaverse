import { useQuery } from '@tanstack/react-query'
import { AlertTriangle, CheckCircle2, Clock, Search } from 'lucide-react'
import { useEffect, useState, type FormEvent } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { AggregatedResult, ContentType, SearchResponse } from '../api/types'
import { Badge, Cover, EmptyState, ErrorState, Spinner } from '../components/ui'
import { cx, languageLabel, typeLabel } from '../lib/format'

const TYPES: ContentType[] = ['MANGA', 'MANHWA', 'MANHUA', 'COMIC']
const EXAMPLES = ['One Piece', 'Berserk', 'Solo Leveling', 'Batman Absolute', 'Amazing Spider-Man', 'Invincible']

export default function SearchPage() {
  const [params, setParams] = useSearchParams()
  const q = params.get('q') ?? ''
  const types = (params.get('tipos') ?? '').split(',').filter(Boolean) as ContentType[]
  const [input, setInput] = useState(q)
  useEffect(() => setInput(q), [q])

  const { data, isFetching, error, refetch } = useQuery({
    queryKey: ['search', q, types.join(',')],
    queryFn: async () => (await api.get<SearchResponse>('/search', { params: { q, types: types.join(',') || undefined } })).data,
    enabled: q.trim().length > 0,
    staleTime: 5 * 60_000,
  })

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (input.trim()) setParams({ q: input.trim(), ...(types.length ? { tipos: types.join(',') } : {}) })
  }
  const toggleType = (t: ContentType) => {
    const next = types.includes(t) ? types.filter((x) => x !== t) : [...types, t]
    setParams({ ...(q ? { q } : {}), ...(next.length ? { tipos: next.join(',') } : {}) })
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-white">Pesquisa global</h1>
        <p className="text-sm text-zinc-400">Consulta todas as fontes habilitadas ao mesmo tempo. Resultados com tradução em português aparecem primeiro.</p>
      </div>
      <form onSubmit={submit} className="flex gap-2">
        <div className="relative flex-1">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-zinc-500" />
          <input autoFocus className="input py-3 pl-10 text-base" value={input} onChange={(e) => setInput(e.target.value)} placeholder="Ex.: Solo Leveling, Batman Absolute…" />
        </div>
        <button className="btn-primary px-5">Pesquisar</button>
      </form>
      <div className="flex flex-wrap items-center gap-2">
        {TYPES.map((t) => (
          <button key={t} className={cx('chip', types.includes(t) && 'chip-active')} onClick={() => toggleType(t)}>{typeLabel[t]}</button>
        ))}
      </div>

      {!q && (
        <div className="flex flex-wrap gap-2">
          <span className="text-sm text-zinc-500">Experimente:</span>
          {EXAMPLES.map((e) => <button key={e} className="chip" onClick={() => setParams({ q: e })}>{e}</button>)}
        </div>
      )}

      {data && (
        <div className="flex flex-wrap gap-2">
          {data.providers.map((p) => (
            <span key={p.code} title={p.message ?? ''} className={cx('inline-flex items-center gap-1.5 rounded-lg border px-2.5 py-1 text-xs',
              p.status === 'OK' ? 'border-emerald-500/30 text-emerald-300' : p.status === 'SKIPPED' ? 'border-ink-600 text-zinc-500' : 'border-amber-500/30 text-amber-300')}>
              {p.status === 'OK' ? <CheckCircle2 className="h-3.5 w-3.5" /> : p.status === 'TIMEOUT' ? <Clock className="h-3.5 w-3.5" /> : <AlertTriangle className="h-3.5 w-3.5" />}
              {p.name}: {p.status === 'OK' ? `${p.count} resultado(s)` : p.status === 'SKIPPED' ? 'não consultada (limite)' : p.message}
              {p.status !== 'SKIPPED' && <span className="text-zinc-500">· {(p.tookMs / 1000).toFixed(1)}s</span>}
            </span>
          ))}
        </div>
      )}

      {isFetching && (
        <div className="flex items-center gap-3 text-sm text-zinc-400"><Spinner /> Consultando fontes…</div>
      )}
      {error && <ErrorState message={errorMessage(error)} onRetry={refetch} />}
      {data && !isFetching && data.results.length === 0 && (
        <EmptyState title="Nenhum resultado" description="Tente outro nome, um título alternativo, ou habilite mais fontes em Fontes e extensões." />
      )}
      {data && data.results.length > 0 && (
        <div className={cx('grid gap-4 md:grid-cols-2 2xl:grid-cols-3', isFetching && 'opacity-60')}>
          {data.results.map((r) => <ResultCard key={r.key} result={r} />)}
        </div>
      )}
    </div>
  )
}

function ResultCard({ result: r }: { result: AggregatedResult }) {
  const primary = r.sources[0]
  const to = r.libraryContentId ? `/conteudo/${r.libraryContentId}` : `/titulo/${encodeURIComponent(primary.providerCode)}/${encodeURIComponent(primary.externalId)}`
  return (
    <div className="card group flex gap-4 p-3 transition-colors animate-fade-in hover:border-accent-500/40">
      <Link to={to} className="shrink-0"><Cover src={r.coverUrl} alt={r.title} className="h-40 w-28 rounded-lg" /></Link>
      <div className="flex min-w-0 flex-1 flex-col gap-2">
        <div>
          <Link to={to} className="line-clamp-2 font-semibold text-zinc-100 hover:text-white">{r.title}</Link>
          {r.altTitles.length > 0 && <p className="line-clamp-1 text-xs text-zinc-500">{r.altTitles.slice(0, 3).join(' · ')}</p>}
        </div>
        <div className="flex flex-wrap gap-1">
          {r.type && <Badge tone={r.type === 'COMIC' ? 'blue' : 'accent'}>{typeLabel[r.type]}</Badge>}
          {r.hasPreferredLanguage && <Badge tone="green">PT-BR</Badge>}
          {r.libraryContentId && <Badge tone="amber">Na biblioteca</Badge>}
          {r.year && <Badge>{r.year}</Badge>}
          {r.publisher && <Badge>{r.publisher}</Badge>}
        </div>
        {r.description && <p className="line-clamp-3 text-xs leading-relaxed text-zinc-400">{r.description}</p>}
        <div className="mt-auto flex flex-wrap gap-1.5 pt-1">
          {r.sources.map((s) => (
            <Link key={s.providerCode + s.externalId} to={`/titulo/${encodeURIComponent(s.providerCode)}/${encodeURIComponent(s.externalId)}`}
              className="rounded-md bg-ink-700 px-2 py-0.5 text-[11px] text-zinc-300 hover:bg-ink-600 hover:text-white" title={s.languages.map(languageLabel).join(', ')}>
              {s.providerName}
            </Link>
          ))}
        </div>
      </div>
    </div>
  )
}
