import { useInfiniteQuery } from '@tanstack/react-query'
import { ArrowLeft, Clock, Flame, Tags } from 'lucide-react'
import { useEffect, useRef } from 'react'
import { Link, useSearchParams } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { CatalogKind, CatalogListing } from '../api/types'
import { CardGrid, CardGridSkeleton, TitleCard } from '../components/TitleCard'
import { EmptyState, ErrorState, Spinner } from '../components/ui'
import { titleLink, useCatalogSources, useGenres } from '../lib/catalog'
import { cx } from '../lib/format'

/** Lista completa de uma fonte: populares, lançamentos ou uma categoria, carregando mais ao rolar. */
export default function CatalogListPage() {
  const [params, setParams] = useSearchParams()
  const source = params.get('fonte') ?? ''
  const kind = (params.get('tipo') as CatalogKind | null) ?? 'POPULAR'
  const genre = params.get('genero')
  const genreName = params.get('nome')
  const sources = useCatalogSources()
  const genres = useGenres(source || undefined)
  const info = sources.data?.find((s) => s.code === source)

  const query = useInfiniteQuery({
    queryKey: ['catalog', 'listing', source, kind, genre],
    queryFn: async ({ pageParam }) => (await api.get<CatalogListing>('/catalog/listing', {
      params: { provider: source, kind, genre: kind === 'GENRE' ? genre : undefined, page: pageParam },
    })).data,
    initialPageParam: 1,
    getNextPageParam: (last) => (last.hasNextPage && last.page < 100 ? last.page + 1 : undefined),
    enabled: !!source && (kind !== 'GENRE' || !!genre),
  })

  // carrega a próxima página quando o fim da grade aparece
  const sentinel = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const el = sentinel.current
    if (!el) return
    const io = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting && query.hasNextPage && !query.isFetchingNextPage) query.fetchNextPage()
    }, { rootMargin: '600px' })
    io.observe(el)
    return () => io.disconnect()
  }, [query])

  const set = (patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    Object.entries(patch).forEach(([k, v]) => (v == null ? next.delete(k) : next.set(k, v)))
    setParams(next)
  }
  // a mesma obra pode vir repetida entre páginas
  const seen = new Set<string>()
  const items = (query.data?.pages ?? []).flatMap((p) => p.items).filter((t) => {
    const key = t.providerCode + t.externalId
    return !seen.has(key) && seen.add(key)
  })
  const heading = kind === 'GENRE' ? genreName ?? 'Categoria' : kind === 'LATEST' ? 'Lançamentos recentes' : 'Populares'

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <Link to="/explorar" className="mb-1 inline-flex items-center gap-1 text-xs text-zinc-400 hover:text-white"><ArrowLeft className="h-3.5 w-3.5" /> Explorar</Link>
          <h1 className="text-2xl font-bold text-white">{heading}</h1>
          <p className="text-sm text-zinc-400">{info?.name ?? source}</p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <div className="flex rounded-lg bg-ink-800 p-1">
            <button className={cx('btn px-3 py-1.5', kind === 'POPULAR' ? 'bg-ink-600 text-white' : 'text-zinc-400')} onClick={() => set({ tipo: 'POPULAR', genero: null, nome: null })}><Flame className="h-4 w-4" /> Populares</button>
            {info?.supportsLatest !== false && (
              <button className={cx('btn px-3 py-1.5', kind === 'LATEST' ? 'bg-ink-600 text-white' : 'text-zinc-400')} onClick={() => set({ tipo: 'LATEST', genero: null, nome: null })}><Clock className="h-4 w-4" /> Lançamentos</button>
            )}
          </div>
          {!!genres.data?.length && (
            <label className="flex items-center gap-2 text-sm text-zinc-400"><Tags className="h-4 w-4" />
              <select className="input w-auto" value={kind === 'GENRE' ? genre ?? '' : ''}
                onChange={(e) => {
                  const g = genres.data!.find((x) => x.id === e.target.value)
                  if (g) set({ tipo: 'GENRE', genero: g.id, nome: g.name })
                }}>
                <option value="" disabled>Categoria…</option>
                {genres.data.map((g) => <option key={g.id} value={g.id}>{g.name}</option>)}
              </select>
            </label>
          )}
        </div>
      </div>

      {!source ? <EmptyState title="Fonte não informada" action={<Link to="/explorar" className="btn-primary">Voltar ao Explorar</Link>} />
        : query.isLoading ? <CardGridSkeleton count={18} />
          : query.error && !items.length ? <ErrorState message={errorMessage(query.error)} onRetry={() => query.refetch()} />
            : !items.length ? <EmptyState title="Nada encontrado" description="A fonte não devolveu títulos aqui." />
              : (
                <CardGrid>
                  {items.map((t) => (
                    <TitleCard key={t.providerCode + t.externalId} to={titleLink(t)} title={t.title} coverUrl={t.coverUrl} type={t.type}
                      subtitle={t.year ? String(t.year) : undefined} />
                  ))}
                </CardGrid>
              )}
      <div ref={sentinel} className="flex h-16 items-center justify-center">
        {query.isFetchingNextPage && <Spinner />}
        {query.error && items.length > 0 && <button className="btn-secondary" onClick={() => query.fetchNextPage()}>Erro ao carregar mais · tentar de novo</button>}
      </div>
    </div>
  )
}
