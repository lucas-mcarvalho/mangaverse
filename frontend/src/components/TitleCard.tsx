import type { ReactNode } from 'react'
import { Link } from 'react-router'
import type { ContentType } from '../api/types'
import { typeLabel } from '../lib/format'
import { Cover, ProgressBar } from './ui'

export function TitleCard({ to, title, coverUrl, type, subtitle, progress, badges, overlay }: {
  to: string; title: string; coverUrl: string | null; type?: ContentType | null; subtitle?: ReactNode
  progress?: number; badges?: ReactNode; overlay?: ReactNode
}) {
  return (
    <Link to={to} className="group block animate-fade-in">
      <div className="relative overflow-hidden rounded-xl ring-1 ring-ink-700 transition-all duration-300 group-hover:-translate-y-1 group-hover:shadow-2xl group-hover:shadow-accent-900/30 group-hover:ring-accent-500/50">
        <Cover src={coverUrl} alt={title} className="aspect-[2/3] w-full" />
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/80 via-black/0 to-black/0 opacity-80" />
        <div className="absolute left-2 top-2 flex flex-wrap gap-1">
          {type && <span className={`rounded-md px-1.5 py-0.5 text-[11px] font-semibold text-white shadow ${type === 'COMIC' ? 'bg-sky-600/90' : 'bg-accent-600/90'}`}>{typeLabel[type]}</span>}
          {badges}
        </div>
        {overlay && <div className="absolute bottom-2 right-2">{overlay}</div>}
        {progress != null && progress > 0 && <ProgressBar value={progress} className="absolute inset-x-0 bottom-0 h-1 rounded-none" />}
      </div>
      <div className="mt-2 px-0.5">
        <h3 className="line-clamp-2 text-sm font-medium leading-snug text-zinc-100 group-hover:text-white">{title}</h3>
        {subtitle && <div className="mt-0.5 text-xs text-zinc-400">{subtitle}</div>}
      </div>
    </Link>
  )
}

export function CardGrid({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 2xl:grid-cols-7">{children}</div>
}

export function CardGridSkeleton({ count = 12 }: { count?: number }) {
  return (
    <CardGrid>
      {Array.from({ length: count }).map((_, i) => (
        <div key={i}>
          <div className="skeleton aspect-[2/3] rounded-xl" />
          <div className="skeleton mt-2 h-3 w-3/4 rounded" />
        </div>
      ))}
    </CardGrid>
  )
}

export function HorizontalRow({ children }: { children: ReactNode }) {
  return <div className="-mx-1 flex snap-x gap-4 overflow-x-auto px-1 pb-2 [&>*]:w-36 [&>*]:shrink-0 [&>*]:snap-start sm:[&>*]:w-40">{children}</div>
}
