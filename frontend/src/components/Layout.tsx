import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Bell, Compass, Download, Home, Library, LogOut, Menu, Puzzle, Search, Settings, X } from 'lucide-react'
import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router'
import { api } from '../api/client'
import type { DownloadSummary, NotificationView } from '../api/types'
import { useAuth } from '../hooks/useAuth'
import { cx, timeAgo } from '../lib/format'
import { Cover } from './ui'

const nav = [
  { to: '/', label: 'Dashboard', icon: Home, end: true },
  { to: '/explorar', label: 'Explorar', icon: Compass },
  { to: '/pesquisa', label: 'Pesquisa global', icon: Search },
  { to: '/biblioteca', label: 'Minha Biblioteca', icon: Library },
  { to: '/downloads', label: 'Downloads', icon: Download },
  { to: '/fontes', label: 'Fontes e extensões', icon: Puzzle },
  { to: '/configuracoes', label: 'Configurações', icon: Settings },
]

export function Layout() {
  const [open, setOpen] = useState(false)
  const location = useLocation()
  useEffect(() => setOpen(false), [location.pathname])
  const { data: summary } = useQuery({
    queryKey: ['downloads', 'summary'],
    queryFn: async () => (await api.get<DownloadSummary>('/downloads/summary')).data,
    refetchInterval: 5000,
  })
  const active = (summary?.downloading ?? 0) + (summary?.queued ?? 0)

  return (
    <div className="flex min-h-full">
      <aside className={cx('fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-ink-700 bg-ink-950/95 backdrop-blur transition-transform lg:translate-x-0', open ? 'translate-x-0' : '-translate-x-full')}>
        <div className="flex h-16 items-center gap-2.5 px-5">
          <img src="/favicon.svg" alt="" className="h-8 w-8" />
          <span className="bg-gradient-to-r from-accent-400 to-pink-accent bg-clip-text text-lg font-bold tracking-tight text-transparent">MangaVerse</span>
          <button className="btn-ghost ml-auto p-1.5 lg:hidden" onClick={() => setOpen(false)}><X className="h-5 w-5" /></button>
        </div>
        <nav className="flex-1 space-y-1 px-3 py-2">
          {nav.map(({ to, label, icon: Icon, end }) => (
            <NavLink key={to} to={to} end={end} className={({ isActive }) => cx(
              'flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm font-medium transition-colors',
              isActive ? 'bg-accent-600/15 text-accent-400' : 'text-zinc-400 hover:bg-ink-800 hover:text-zinc-100')}>
              <Icon className="h-[18px] w-[18px]" />
              <span className="flex-1">{label}</span>
              {to === '/downloads' && active > 0 && (
                <span className="rounded-full bg-accent-600 px-1.5 py-0.5 text-[10px] font-bold text-white">{active}</span>
              )}
            </NavLink>
          ))}
        </nav>
        <UserBox />
      </aside>
      {open && <div className="fixed inset-0 z-30 bg-black/60 lg:hidden" onClick={() => setOpen(false)} />}
      <div className="flex min-w-0 flex-1 flex-col lg:pl-64">
        <header className="sticky top-0 z-20 flex h-16 items-center gap-3 border-b border-ink-700/60 bg-ink-900/80 px-4 backdrop-blur-md sm:px-6">
          <button className="btn-ghost p-2 lg:hidden" onClick={() => setOpen(true)} aria-label="Menu"><Menu className="h-5 w-5" /></button>
          <QuickSearch />
          <Notifications />
        </header>
        <main className="flex-1 px-4 py-6 sm:px-6 lg:px-8">
          <Outlet />
        </main>
      </div>
    </div>
  )
}

function QuickSearch() {
  const navigate = useNavigate()
  const [q, setQ] = useState('')
  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (q.trim()) navigate(`/pesquisa?q=${encodeURIComponent(q.trim())}`)
  }
  return (
    <form onSubmit={submit} className="relative max-w-xl flex-1">
      <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-zinc-500" />
      <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Pesquisar mangás, manhwas e HQs em todas as fontes…" className="input rounded-full pl-9" />
    </form>
  )
}

function Notifications() {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  const qc = useQueryClient()
  const { data } = useQuery({
    queryKey: ['notifications'],
    queryFn: async () => (await api.get<{ unseen: number; items: NotificationView[] }>('/notifications', { params: { limit: 30 } })).data,
    refetchInterval: 60_000,
  })
  const markSeen = useMutation({
    mutationFn: () => api.post('/notifications/seen'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  })
  useEffect(() => {
    const onClick = (e: MouseEvent) => ref.current && !ref.current.contains(e.target as Node) && setOpen(false)
    document.addEventListener('mousedown', onClick)
    return () => document.removeEventListener('mousedown', onClick)
  }, [])
  const toggle = () => {
    setOpen(!open)
    if (!open && data?.unseen) markSeen.mutate()
  }
  return (
    <div className="relative ml-auto" ref={ref}>
      <button className="btn-ghost relative p-2" onClick={toggle} aria-label="Notificações">
        <Bell className="h-5 w-5" />
        {!!data?.unseen && <span className="absolute right-1 top-1 h-4 min-w-4 rounded-full bg-pink-accent px-1 text-[10px] font-bold leading-4 text-white">{data.unseen > 99 ? '99+' : data.unseen}</span>}
      </button>
      {open && (
        <div className="card absolute right-0 top-12 z-50 w-[min(92vw,380px)] overflow-hidden shadow-2xl animate-fade-in">
          <div className="border-b border-ink-700 px-4 py-3 text-sm font-semibold">Notificações</div>
          <ul className="max-h-96 divide-y divide-ink-700 overflow-y-auto">
            {!data?.items.length && <li className="p-6 text-center text-sm text-zinc-500">Nenhuma notificação ainda.</li>}
            {data?.items.map((n) => (
              <li key={n.id}>
                <Link to={n.chapterId && n.type === 'NEW_CHAPTER' ? `/conteudo/${n.contentId}` : `/conteudo/${n.contentId}`} onClick={() => setOpen(false)} className={cx('flex gap-3 px-4 py-3 hover:bg-ink-800', !n.seen && 'bg-accent-600/5')}>
                  <Cover src={n.coverUrl} alt={n.contentTitle} className="h-14 w-10 shrink-0 rounded" />
                  <div className="min-w-0 flex-1">
                    <p className={cx('text-sm', n.type.endsWith('FAILED') ? 'text-red-300' : 'text-zinc-200')}>{n.message}</p>
                    <p className="mt-1 text-xs text-zinc-500">{timeAgo(n.createdAt)}</p>
                  </div>
                </Link>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function UserBox() {
  const { user, logout } = useAuth()
  return (
    <div className="flex items-center gap-3 border-t border-ink-700 p-4">
      <div className="flex h-9 w-9 items-center justify-center rounded-full bg-gradient-to-br from-accent-500 to-pink-accent text-sm font-bold uppercase text-white">{user?.username.slice(0, 1)}</div>
      <div className="min-w-0 flex-1">
        <p className="truncate text-sm font-medium text-zinc-100">{user?.username}</p>
        <p className="text-xs text-zinc-500">Administrador</p>
      </div>
      <button className="btn-ghost p-2" onClick={logout} title="Sair"><LogOut className="h-4 w-4" /></button>
    </div>
  )
}
