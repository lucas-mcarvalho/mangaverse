import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Flame, Clock } from 'lucide-react'
import { useSearchParams } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { RemoteTitle } from '../api/types'
import { CardGrid, CardGridSkeleton, TitleCard } from '../components/TitleCard'
import { EmptyState, ErrorState } from '../components/ui'
import { cx } from '../lib/format'

interface ExploreResponse { provider: string; kind: 'POPULAR' | 'LATEST'; page: number; items: RemoteTitle[]; providers: { code: string; name: string }[] }

export default function ExplorePage() {
  const [params, setParams] = useSearchParams()
  const provider = params.get('fonte') ?? 'mangadex'
  const kind = (params.get('tipo') as 'POPULAR' | 'LATEST') ?? 'POPULAR'
  const page = Number(params.get('pagina') ?? '1')
  const update = (patch: Record<string, string>) => setParams({ fonte: provider, tipo: kind, pagina: String(page), ...patch })

  const { data, isLoading, isFetching, error, refetch } = useQuery({
    queryKey: ['explore', provider, kind, page],
    queryFn: async () => (await api.get<ExploreResponse>('/explore', { params: { provider, kind, page } })).data,
    placeholderData: (prev) => prev,
  })

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">Explorar</h1>
          <p className="text-sm text-zinc-400">Títulos populares e atualizados recentemente, priorizando traduções no seu idioma.</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <select className="input w-auto" value={provider} onChange={(e) => update({ fonte: e.target.value, pagina: '1' })}>
            {(data?.providers ?? [{ code: 'mangadex', name: 'MangaDex' }]).map((p) => <option key={p.code} value={p.code}>{p.name}</option>)}
          </select>
          <div className="flex rounded-lg bg-ink-800 p-1">
            <button className={cx('btn px-3 py-1.5', kind === 'POPULAR' ? 'bg-ink-600 text-white' : 'text-zinc-400')} onClick={() => update({ tipo: 'POPULAR', pagina: '1' })}><Flame className="h-4 w-4" /> Populares</button>
            <button className={cx('btn px-3 py-1.5', kind === 'LATEST' ? 'bg-ink-600 text-white' : 'text-zinc-400')} onClick={() => update({ tipo: 'LATEST', pagina: '1' })}><Clock className="h-4 w-4" /> Recentes</button>
          </div>
        </div>
      </div>
      {isLoading ? <CardGridSkeleton count={18} /> : error ? <ErrorState message={errorMessage(error)} onRetry={refetch} /> : !data?.items.length ? (
        <EmptyState title="Nada encontrado" description="Esta fonte não retornou títulos para esta página." />
      ) : (
        <div className={cx('transition-opacity', isFetching && 'opacity-60')}>
          <CardGrid>
            {data.items.map((t) => (
              <TitleCard key={t.providerCode + t.externalId} to={`/titulo/${encodeURIComponent(t.providerCode)}/${encodeURIComponent(t.externalId)}`}
                title={t.title} coverUrl={t.coverUrl} type={t.type} subtitle={t.year ? String(t.year) : t.providerName} />
            ))}
          </CardGrid>
        </div>
      )}
      <div className="flex items-center justify-center gap-3">
        <button className="btn-secondary" disabled={page <= 1} onClick={() => update({ pagina: String(page - 1) })}><ChevronLeft className="h-4 w-4" /> Anterior</button>
        <span className="text-sm text-zinc-400">Página {page}</span>
        <button className="btn-secondary" disabled={!data?.items.length} onClick={() => update({ pagina: String(page + 1) })}>Próxima <ChevronRight className="h-4 w-4" /></button>
      </div>
    </div>
  )
}
