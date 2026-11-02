import { useQuery } from '@tanstack/react-query'
import { BookOpen, BookMarked, CheckCircle2, Download, Heart, Library, Radar, Sparkles } from 'lucide-react'
import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { Dashboard } from '../api/types'
import { CardGridSkeleton, HorizontalRow, TitleCard } from '../components/TitleCard'
import { RemoveFromLibraryButton } from '../components/RemoveFromLibraryButton'
import { Cover, EmptyState, ErrorState, ProgressBar, SectionTitle } from '../components/ui'
import { downloadStatusLabel, timeAgo } from '../lib/format'

export default function DashboardPage() {
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['dashboard'],
    queryFn: async () => (await api.get<Dashboard>('/dashboard')).data,
    refetchInterval: 15_000,
  })
  if (isLoading) return <CardGridSkeleton />
  if (error || !data) return <ErrorState message={errorMessage(error)} onRetry={refetch} />
  const s = data.stats
  const empty = s.titles === 0

  return (
    <div className="space-y-10">
      <div>
        <h1 className="text-2xl font-bold text-white">Bem-vindo de volta</h1>
        <p className="text-sm text-zinc-400">Acompanhe suas leituras, novidades e downloads.</p>
      </div>

      <div className="grid grid-cols-2 gap-3 md:grid-cols-4 xl:grid-cols-8">
        <Stat icon={<Library />} label="Títulos" value={s.titles} />
        <Stat icon={<BookOpen />} label="Mangás" value={s.mangas} />
        <Stat icon={<BookMarked />} label="HQs" value={s.comics} />
        <Stat icon={<Sparkles />} label="Lendo" value={s.reading} />
        <Stat icon={<CheckCircle2 />} label="Concluídos" value={s.completed} />
        <Stat icon={<Heart />} label="Favoritos" value={s.favorites} />
        <Stat icon={<Radar />} label="Monitorados" value={s.monitored} />
        <Stat icon={<Download />} label="Cap. baixados" value={s.chaptersDownloaded} />
      </div>

      {empty && (
        <EmptyState
          title="Sua biblioteca está vazia"
          description="Pesquise um título em todas as fontes, explore os populares ou importe arquivos CBZ/CBR/PDF que você já possui."
          action={<div className="flex flex-wrap justify-center gap-2"><Link to="/pesquisa" className="btn-primary">Pesquisar</Link><Link to="/biblioteca?importar=1" className="btn-secondary">Importar arquivos</Link></div>}
        />
      )}

      {data.continueReading.length > 0 && (
        <section>
          <SectionTitle>Continuar lendo</SectionTitle>
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            {data.continueReading.slice(0, 6).map((c) => (
              <div key={c.contentId} className="card p-3">
              <Link to={`/ler/${c.chapterId}`} className="group flex gap-4 transition-colors hover:border-accent-500/40">
                <Cover src={c.coverUrl} alt={c.title} className="h-24 w-16 shrink-0 rounded-lg" />
                <div className="flex min-w-0 flex-1 flex-col justify-center gap-1.5">
                  <h3 className="truncate font-medium text-zinc-100 group-hover:text-white">{c.title}</h3>
                  <p className="text-sm text-zinc-400">{c.chapterLabel} {c.totalPages > 0 && `· pág. ${c.page + 1}/${c.totalPages}`}</p>
                  <ProgressBar value={c.completed ? 100 : c.totalPages ? ((c.page + 1) / c.totalPages) * 100 : 0} tone={c.completed ? 'green' : 'accent'} />
                </div>
              </Link>
              <div className="mt-2 flex justify-end"><RemoveFromLibraryButton entryId={c.entryId} contentId={c.contentId} title={c.title} /></div>
              </div>
            ))}
          </div>
        </section>
      )}

      {data.latestChapters.length > 0 && (
        <section>
          <SectionTitle>Últimos capítulos encontrados</SectionTitle>
          <div className="card divide-y divide-ink-700">
            {data.latestChapters.slice(0, 8).map((n) => (
              <Link key={n.id} to={n.chapterId ? `/ler/${n.chapterId}` : `/conteudo/${n.contentId}`} className="flex items-center gap-3 px-4 py-3 hover:bg-ink-800">
                <Cover src={n.coverUrl} alt={n.contentTitle} className="h-12 w-9 shrink-0 rounded" />
                <span className="flex-1 text-sm text-zinc-200">{n.message}</span>
                <span className="shrink-0 text-xs text-zinc-500">{timeAgo(n.createdAt)}</span>
              </Link>
            ))}
          </div>
        </section>
      )}

      {data.activeDownloads.length > 0 && (
        <section>
          <SectionTitle action={<Link to="/downloads" className="text-sm text-accent-400 hover:underline">Ver todos</Link>}>Downloads em andamento</SectionTitle>
          <div className="grid gap-3 md:grid-cols-2">
            {data.activeDownloads.map((d) => (
              <div key={d.id} className="card flex items-center gap-3 p-3">
                <Cover src={d.coverUrl} alt={d.contentTitle} className="h-14 w-10 shrink-0 rounded" />
                <div className="min-w-0 flex-1 space-y-1.5">
                  <div className="flex justify-between gap-2 text-sm"><span className="truncate">{d.contentTitle} — {d.chapterLabel}</span><span className="shrink-0 text-zinc-400">{downloadStatusLabel[d.status]}</span></div>
                  <ProgressBar value={d.progress} />
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      <Row title="Recentemente adicionados" items={data.recentlyAdded} />
      <Row title="Mangás favoritos" items={data.favoriteMangas} />
      <Row title="HQs favoritas" items={data.favoriteComics} />
    </div>
  )
}

function Stat({ icon, label, value }: { icon: ReactNode; label: string; value: number }) {
  return (
    <div className="card flex items-center gap-3 p-3.5">
      <div className="rounded-lg bg-accent-600/15 p-2 text-accent-400 [&>svg]:h-4 [&>svg]:w-4">{icon}</div>
      <div>
        <div className="text-lg font-bold leading-none text-white">{value}</div>
        <div className="mt-1 text-[11px] text-zinc-400">{label}</div>
      </div>
    </div>
  )
}

function Row({ title, items }: { title: string; items: Dashboard['recentlyAdded'] }) {
  if (!items.length) return null
  return (
    <section>
      <SectionTitle>{title}</SectionTitle>
      <HorizontalRow>
        {items.map((i) => (
          <TitleCard key={i.entryId} to={`/conteudo/${i.contentId}`} title={i.title} coverUrl={i.coverUrl} type={i.type}
            progress={i.progressPercent} subtitle={`${i.readChapters}/${i.totalChapters} lidos`} />
        ))}
      </HorizontalRow>
    </section>
  )
}
