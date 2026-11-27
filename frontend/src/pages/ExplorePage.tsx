import { useQuery } from '@tanstack/react-query'
import { ChevronLeft, ChevronRight, Clock, Flame, LayoutGrid, Tags } from 'lucide-react'
import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { CatalogKind, CatalogListing, CatalogSource } from '../api/types'
import { HorizontalRow, TitleCard } from '../components/TitleCard'
import { Cover, EmptyState, ErrorState, PageLoader, SectionTitle } from '../components/ui'
import { listLink, titleLink, useCatalogSources, useGenres } from '../lib/catalog'
import { cx, imageUrl, languageLabel } from '../lib/format'

const SOURCE_KEY = 'mangaverse.catalog.source'

function useListing(source: string | undefined, kind: CatalogKind, enabled = true) {
  return useQuery({
    queryKey: ['catalog', 'listing', source, kind, null, 1],
    queryFn: async () => (await api.get<CatalogListing>('/catalog/listing', { params: { provider: source, kind, page: 1 } })).data,
    enabled: !!source && enabled, staleTime: 10 * 60_000,
  })
}

/** Fonte padrão: a última escolhida, senão a primeira em português com lançamentos, senão a primeira. */
function defaultSource(sources: CatalogSource[]): CatalogSource | undefined {
  const saved = localStorage.getItem(SOURCE_KEY)
  return sources.find((s) => s.code === saved)
    ?? sources.find((s) => s.language === 'pt-br' && s.supportsLatest && s.type === 'SUWAYOMI_SOURCE')
    ?? sources.find((s) => s.supportsLatest)
    ?? sources[0]
}

export default function ExplorePage() {
  const sources = useCatalogSources()
  const [code, setCode] = useState<string | null>(null)
  const list = sources.data ?? []
  const source = list.find((s) => s.code === code) ?? defaultSource(list)
  const choose = (c: string) => { localStorage.setItem(SOURCE_KEY, c); setCode(c) }
  const others = useMemo(() => list.filter((s) => s.code !== source?.code)
    .sort((a, b) => Number(b.language === source?.language) - Number(a.language === source?.language)).slice(0, 3), [list, source])

  if (sources.isLoading) return <PageLoader />
  if (sources.error) return <ErrorState message={errorMessage(sources.error)} onRetry={sources.refetch} />
  if (!source) {
    return (
      <EmptyState title="Nenhuma fonte para explorar" description="Ative a MangaDex ou instale extensões do Suwayomi em Fontes e extensões para ver destaques, lançamentos e categorias."
        action={<Link to="/fontes" className="btn-primary">Fontes e extensões</Link>} />
    )
  }

  return (
    <div className="space-y-10">
      <div className="space-y-4">
        <div>
          <h1 className="text-2xl font-bold text-white">Explorar</h1>
          <p className="text-sm text-zinc-400">Destaques, lançamentos recentes e categorias das suas fontes.</p>
        </div>
        <div className="-mx-1 flex gap-2 overflow-x-auto px-1 pb-1">
          {list.map((s) => (
            <button key={s.code} onClick={() => choose(s.code)} className={cx('chip shrink-0 gap-2 py-1.5', s.code === source.code && 'chip-active')}>
              <Cover src={s.iconUrl} alt="" className="h-5 w-5 rounded" />
              {s.name}{s.language && s.type === 'SUWAYOMI_SOURCE' && <span className="text-zinc-500">{s.language}</span>}
              {s.adult && <span className="text-pink-300">18+</span>}
            </button>
          ))}
        </div>
      </div>

      <Featured key={`f-${source.code}`} source={source} />
      {source.supportsLatest && <Row key={`l-${source.code}`} title="Lançamentos recentes" icon={<Clock />} source={source} kind="LATEST" />}
      <Genres key={`g-${source.code}`} source={source} />
      <Row key={`p-${source.code}`} title="Populares" icon={<Flame />} source={source} kind="POPULAR" skip={5} />
      {others.map((s) => (
        <Row key={`o-${s.code}`} title={`${s.supportsLatest ? 'Novidades' : 'Populares'} em ${s.name}`} subtitle={languageLabel(s.language)}
          icon={<LayoutGrid />} source={s} kind={s.supportsLatest ? 'LATEST' : 'POPULAR'} />
      ))}
    </div>
  )
}

/** Carrossel com os 5 mais populares da fonte. */
function Featured({ source }: { source: CatalogSource }) {
  const { data, isLoading, error, refetch } = useListing(source.code, 'POPULAR')
  const items = (data?.items ?? []).slice(0, 5)
  const [index, setIndex] = useState(0)
  useEffect(() => {
    if (items.length < 2) return
    const t = setInterval(() => setIndex((i) => (i + 1) % items.length), 8000)
    return () => clearInterval(t)
  }, [items.length])

  if (isLoading) return <div className="skeleton h-72 rounded-2xl" />
  if (error) return <ErrorState message={errorMessage(error)} onRetry={refetch} />
  if (!items.length) return null
  const t = items[Math.min(index, items.length - 1)]
  const bg = imageUrl(t.coverUrl)
  const go = (d: number) => setIndex((i) => (i + d + items.length) % items.length)

  return (
    <section className="relative overflow-hidden rounded-2xl border border-ink-700 bg-ink-800">
      {bg && <img src={bg} alt="" className="absolute inset-0 h-full w-full scale-110 object-cover opacity-40 blur-2xl" />}
      <div className="absolute inset-0 bg-gradient-to-r from-ink-950 via-ink-950/85 to-ink-950/30" />
      <div className="relative flex gap-6 p-5 sm:p-8">
        <Link to={titleLink(t)} className="shrink-0">
          <Cover src={t.coverUrl} alt={t.title} className="aspect-[2/3] w-32 rounded-xl shadow-2xl ring-1 ring-white/10 sm:w-44" />
        </Link>
        <div className="flex min-w-0 flex-1 flex-col justify-center gap-3">
          <span className="text-xs font-bold uppercase tracking-widest text-accent-400">#{index + 1} em alta · {source.name}</span>
          <Link to={titleLink(t)} className="line-clamp-2 text-2xl font-bold text-white hover:underline sm:text-3xl">{t.title}</Link>
          {t.description && <p className="line-clamp-3 max-w-2xl text-sm text-zinc-300">{t.description}</p>}
          <div className="flex flex-wrap items-center gap-2">
            <Link to={titleLink(t)} className="btn-primary">Ver detalhes</Link>
            <Link to={listLink(source.code, 'POPULAR')} className="btn-secondary">Todos os populares</Link>
          </div>
        </div>
      </div>
      {items.length > 1 && (
        <div className="relative flex items-center justify-between px-5 pb-4 sm:px-8">
          <div className="flex gap-1.5">
            {items.map((_, i) => (
              <button key={i} onClick={() => setIndex(i)} aria-label={`Destaque ${i + 1}`}
                className={cx('h-1.5 rounded-full transition-all', i === index ? 'w-6 bg-accent-400' : 'w-1.5 bg-white/30')} />
            ))}
          </div>
          <div className="flex gap-1">
            <button className="btn-ghost p-1.5" onClick={() => go(-1)} aria-label="Anterior"><ChevronLeft className="h-5 w-5" /></button>
            <button className="btn-ghost p-1.5" onClick={() => go(1)} aria-label="Próximo"><ChevronRight className="h-5 w-5" /></button>
          </div>
        </div>
      )}
    </section>
  )
}

function Row({ title, subtitle, icon, source, kind, skip = 0 }: {
  title: string; subtitle?: string; icon: ReactNode; source: CatalogSource; kind: CatalogKind; skip?: number
}) {
  const { data, isLoading, error, refetch } = useListing(source.code, kind)
  const items = (data?.items ?? []).slice(skip)
  return (
    <section>
      <SectionTitle action={<Link to={listLink(source.code, kind)} className="text-sm text-accent-400 hover:underline">Ver tudo</Link>}>
        <span className="flex items-center gap-2 [&>svg]:h-5 [&>svg]:w-5 [&>svg]:text-accent-400">{icon}{title}
          {subtitle && <span className="text-sm font-normal text-zinc-500">{subtitle}</span>}</span>
      </SectionTitle>
      {isLoading ? (
        <HorizontalRow>{Array.from({ length: 7 }).map((_, i) => <div key={i}><div className="skeleton aspect-[2/3] rounded-xl" /><div className="skeleton mt-2 h-3 w-3/4 rounded" /></div>)}</HorizontalRow>
      ) : error ? (
        <div className="card flex items-center justify-between gap-3 p-4 text-sm text-zinc-400">
          <span className="line-clamp-2">{errorMessage(error)}</span>
          <button className="btn-secondary shrink-0" onClick={() => refetch()}>Tentar de novo</button>
        </div>
      ) : !items.length ? (
        <p className="text-sm text-zinc-500">A fonte não devolveu títulos.</p>
      ) : (
        <HorizontalRow>
          {items.slice(0, 24).map((t) => (
            <TitleCard key={t.providerCode + t.externalId} to={titleLink(t)} title={t.title} coverUrl={t.coverUrl} type={t.type}
              subtitle={t.year ? String(t.year) : undefined} />
          ))}
        </HorizontalRow>
      )}
    </section>
  )
}

function Genres({ source }: { source: CatalogSource }) {
  const { data, isLoading } = useGenres(source.code)
  const [all, setAll] = useState(false)
  if (isLoading) return <div className="skeleton h-20 rounded-xl" />
  if (!data?.length) return null
  const shown = all ? data : data.slice(0, 24)
  return (
    <section>
      <SectionTitle action={data.length > 24 && <button className="text-sm text-accent-400 hover:underline" onClick={() => setAll(!all)}>{all ? 'Mostrar menos' : `Ver todas (${data.length})`}</button>}>
        <span className="flex items-center gap-2"><Tags className="h-5 w-5 text-accent-400" />Categorias</span>
      </SectionTitle>
      <div className="flex flex-wrap gap-2">
        {shown.map((g) => <Link key={g.id} to={listLink(source.code, 'GENRE', g)} className="chip py-1.5 text-sm">{g.name}</Link>)}
      </div>
    </section>
  )
}
