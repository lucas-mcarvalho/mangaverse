import { useState, type ReactNode } from 'react'
import { AlertTriangle, ImageOff, Inbox, Loader2, RefreshCw } from 'lucide-react'
import { cx, imageUrl } from '../lib/format'

export function Spinner({ className }: { className?: string }) {
  return <Loader2 className={cx('animate-spin text-accent-400', className ?? 'h-5 w-5')} />
}

export function PageLoader({ label = 'Carregando…' }: { label?: string }) {
  return (
    <div className="flex h-64 flex-col items-center justify-center gap-3 text-zinc-400">
      <Spinner className="h-8 w-8" />
      <span className="text-sm">{label}</span>
    </div>
  )
}

export function ErrorState({ message, onRetry }: { message: string; onRetry?: () => void }) {
  return (
    <div className="card flex flex-col items-center gap-3 p-8 text-center animate-fade-in">
      <div className="rounded-full bg-red-500/10 p-3"><AlertTriangle className="h-6 w-6 text-red-400" /></div>
      <p className="max-w-md text-sm text-zinc-300">{message}</p>
      {onRetry && (
        <button className="btn-secondary" onClick={onRetry}><RefreshCw className="h-4 w-4" /> Tentar novamente</button>
      )}
    </div>
  )
}

export function EmptyState({ title, description, action, icon }: { title: string; description?: string; action?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-xl border border-dashed border-ink-600 p-10 text-center animate-fade-in">
      <div className="rounded-full bg-ink-700 p-3 text-zinc-400">{icon ?? <Inbox className="h-6 w-6" />}</div>
      <h3 className="font-semibold text-zinc-100">{title}</h3>
      {description && <p className="max-w-md text-sm text-zinc-400">{description}</p>}
      {action}
    </div>
  )
}

export function Cover({ src, alt, className }: { src: string | null | undefined; alt: string; className?: string }) {
  const [failed, setFailed] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const url = imageUrl(src)
  return (
    <div className={cx('relative overflow-hidden bg-ink-700', className)}>
      {url && !failed ? (
        <>
          {!loaded && <div className="skeleton absolute inset-0" />}
          <img
            src={url}
            alt={alt}
            loading="lazy"
            onLoad={() => setLoaded(true)}
            onError={() => setFailed(true)}
            className={cx('h-full w-full object-cover transition-opacity duration-300', loaded ? 'opacity-100' : 'opacity-0')}
          />
        </>
      ) : (
        <div className="flex h-full w-full flex-col items-center justify-center gap-2 p-2 text-center text-zinc-500">
          <ImageOff className="h-6 w-6" />
          <span className="line-clamp-3 text-[11px]">{alt}</span>
        </div>
      )}
    </div>
  )
}

export function ProgressBar({ value, className, tone = 'accent' }: { value: number; className?: string; tone?: 'accent' | 'green' | 'red' | 'zinc' }) {
  const color = { accent: 'bg-accent-500', green: 'bg-emerald-500', red: 'bg-red-500', zinc: 'bg-zinc-500' }[tone]
  return (
    <div className={cx('h-1.5 w-full overflow-hidden rounded-full bg-ink-600', className)}>
      <div className={cx('h-full rounded-full transition-all duration-500', color)} style={{ width: `${Math.max(0, Math.min(100, value))}%` }} />
    </div>
  )
}

export function Badge({ children, tone = 'zinc', className }: { children: ReactNode; tone?: 'zinc' | 'accent' | 'green' | 'amber' | 'red' | 'blue'; className?: string }) {
  const tones = {
    zinc: 'bg-ink-700 text-zinc-300 border-ink-600',
    accent: 'bg-accent-600/20 text-accent-400 border-accent-500/30',
    green: 'bg-emerald-500/15 text-emerald-300 border-emerald-500/30',
    amber: 'bg-amber-500/15 text-amber-300 border-amber-500/30',
    red: 'bg-red-500/15 text-red-300 border-red-500/30',
    blue: 'bg-sky-500/15 text-sky-300 border-sky-500/30',
  }
  return <span className={cx('inline-flex items-center gap-1 rounded-md border px-1.5 py-0.5 text-[11px] font-medium', tones[tone], className)}>{children}</span>
}

export function SectionTitle({ children, action }: { children: ReactNode; action?: ReactNode }) {
  return (
    <div className="mb-3 flex items-end justify-between gap-3">
      <h2 className="text-lg font-semibold text-zinc-100">{children}</h2>
      {action}
    </div>
  )
}

export function Toggle({ checked, onChange, label, disabled }: { checked: boolean; onChange: (v: boolean) => void; label?: string; disabled?: boolean }) {
  return (
    <label className={cx('inline-flex cursor-pointer items-center gap-2 text-sm', disabled && 'cursor-not-allowed opacity-50')}>
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={cx('relative h-5 w-9 rounded-full transition-colors', checked ? 'bg-accent-600' : 'bg-ink-600')}
      >
        <span className={cx('absolute top-0.5 h-4 w-4 rounded-full bg-white transition-all', checked ? 'left-4.5' : 'left-0.5')} />
      </button>
      {label && <span className="text-zinc-300">{label}</span>}
    </label>
  )
}
