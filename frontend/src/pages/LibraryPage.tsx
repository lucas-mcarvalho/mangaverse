import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FolderInput, Heart, Plus, Radar, Search, Tag, Trash2 } from 'lucide-react'
import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { Category, ContentType, ImportResult, LibraryItem, LibraryStatus } from '../api/types'
import { DirectoryPicker } from '../components/DirectoryPicker'
import { RemoveFromLibraryButton } from '../components/RemoveFromLibraryButton'
import { Modal } from '../components/Modal'
import { CardGrid, CardGridSkeleton, TitleCard } from '../components/TitleCard'
import { useToast } from '../components/Toast'
import { Badge, EmptyState, ErrorState, Spinner } from '../components/ui'
import { cx, statusLabel, typeLabel } from '../lib/format'

const STATUSES: LibraryStatus[] = ['READING', 'PLAN_TO_READ', 'COMPLETED', 'PAUSED', 'DROPPED']
const TYPES: ContentType[] = ['MANGA', 'MANHWA', 'MANHUA', 'COMIC']

export default function LibraryPage() {
  const [params, setParams] = useSearchParams()
  const [q, setQ] = useState('')
  const type = params.get('tipo') as ContentType | null
  const status = params.get('status') as LibraryStatus | null
  const favorite = params.get('favoritos') === '1'
  const monitored = params.get('monitorados') === '1'
  const categoryId = params.get('categoria')
  const [importOpen, setImportOpen] = useState(params.get('importar') === '1')
  const [catOpen, setCatOpen] = useState(false)

  const set = (key: string, value: string | null) => {
    const next = new URLSearchParams(params)
    if (value == null) next.delete(key)
    else next.set(key, value)
    next.delete('importar')
    setParams(next)
  }

  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['library'],
    queryFn: async () => (await api.get<LibraryItem[]>('/library')).data,
  })
  const { data: categories } = useQuery({
    queryKey: ['categories'],
    queryFn: async () => (await api.get<Category[]>('/library/categories')).data,
  })

  const items = useMemo(() => (data ?? []).filter((i) =>
    (!type || i.type === type) && (!status || i.status === status) && (!favorite || i.favorite) &&
    (!monitored || i.monitored) && (!categoryId || i.categoryId === categoryId) &&
    (!q || i.title.toLowerCase().includes(q.toLowerCase()))), [data, type, status, favorite, monitored, categoryId, q])

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">Minha Biblioteca</h1>
          <p className="text-sm text-zinc-400">{data ? `${data.length} título(s)` : 'Seus mangás e HQs'}</p>
        </div>
        <div className="flex gap-2">
          <button className="btn-secondary" onClick={() => setCatOpen(true)}><Tag className="h-4 w-4" /> Categorias</button>
          <button className="btn-primary" onClick={() => setImportOpen(true)}><FolderInput className="h-4 w-4" /> Importar arquivos locais</button>
        </div>
      </div>

      <div className="flex flex-col gap-3">
        <div className="relative max-w-md">
          <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-zinc-500" />
          <input className="input pl-9" placeholder="Filtrar por título…" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <div className="flex flex-wrap gap-2">
          <button className={cx('chip', !type && 'chip-active')} onClick={() => set('tipo', null)}>Todos</button>
          {TYPES.map((t) => <button key={t} className={cx('chip', type === t && 'chip-active')} onClick={() => set('tipo', type === t ? null : t)}>{typeLabel[t]}</button>)}
          <span className="mx-1 w-px bg-ink-600" />
          {STATUSES.map((s) => <button key={s} className={cx('chip', status === s && 'chip-active')} onClick={() => set('status', status === s ? null : s)}>{statusLabel[s]}</button>)}
          <span className="mx-1 w-px bg-ink-600" />
          <button className={cx('chip', favorite && 'chip-active')} onClick={() => set('favoritos', favorite ? null : '1')}><Heart className="h-3 w-3" /> Favoritos</button>
          <button className={cx('chip', monitored && 'chip-active')} onClick={() => set('monitorados', monitored ? null : '1')}><Radar className="h-3 w-3" /> Monitorados</button>
          {categories?.map((c) => <button key={c.id} className={cx('chip', categoryId === c.id && 'chip-active')} onClick={() => set('categoria', categoryId === c.id ? null : c.id)}><Tag className="h-3 w-3" /> {c.name}</button>)}
        </div>
      </div>

      {isLoading ? <CardGridSkeleton /> : error ? <ErrorState message={errorMessage(error)} onRetry={refetch} /> : items.length === 0 ? (
        <EmptyState title={data?.length ? 'Nenhum título com esses filtros' : 'Sua biblioteca está vazia'}
          description={data?.length ? 'Ajuste os filtros acima.' : 'Adicione títulos pela pesquisa global ou importe seus arquivos locais.'} />
      ) : (
        <CardGrid>
          {items.map((i) => (
            <div key={i.entryId}>
            <TitleCard to={`/conteudo/${i.contentId}`} title={i.title} coverUrl={i.coverUrl} type={i.type} progress={i.progressPercent}
              badges={<>
                {i.favorite && <Badge tone="red"><Heart className="h-3 w-3 fill-current" /></Badge>}
                {i.monitored && <Badge tone="green"><Radar className="h-3 w-3" /></Badge>}
              </>}
              overlay={i.unreadChapters > 0 ? <span className="rounded-md bg-accent-600 px-1.5 py-0.5 text-[11px] font-bold text-white">{i.unreadChapters}</span> : undefined}
              subtitle={<div className="flex justify-between gap-2">
                <span>{statusLabel[i.status]}</span>
                <span>{i.readChapters}/{i.totalChapters}{i.activeDownloads > 0 ? ` · ↓${i.activeDownloads}` : ''}</span>
              </div>} />
            <RemoveFromLibraryButton entryId={i.entryId} contentId={i.contentId} title={i.title} />
            </div>
          ))}
        </CardGrid>
      )}

      <ImportModal open={importOpen} onClose={() => setImportOpen(false)} />
      <CategoriesModal open={catOpen} onClose={() => setCatOpen(false)} categories={categories ?? []} />
    </div>
  )
}

function ImportModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [path, setPath] = useState('')
  const [type, setType] = useState<ContentType>('MANGA')
  const [picker, setPicker] = useState(false)
  const [result, setResult] = useState<ImportResult | null>(null)
  const qc = useQueryClient()
  const toast = useToast()
  const { data: settings } = useQuery({ queryKey: ['settings'], queryFn: async () => (await api.get('/settings')).data, enabled: open })
  useEffect(() => {
    if (open && !path && settings) setPath(type === 'COMIC' ? settings.comicLibraryPath : settings.mangaLibraryPath)
  }, [open, settings, type, path])
  const mutation = useMutation({
    mutationFn: async () => (await api.post<ImportResult>('/library/import', { path, type })).data,
    onSuccess: (r) => {
      setResult(r)
      qc.invalidateQueries({ queryKey: ['library'] })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
      toast.success(`${r.chaptersAdded} capítulo(s) importado(s) em ${r.seriesFound} série(s).`)
    },
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <Modal open={open} onClose={onClose} title="Importar arquivos locais">
      <div className="space-y-4">
        <p className="text-sm text-zinc-400">
          Escolha uma pasta de biblioteca (cada subpasta é uma série) ou a pasta de uma série (arquivos CBZ, CBR, PDF ou subpastas de imagens).
          Os arquivos não são movidos nem renomeados.
        </p>
        <div className="flex gap-2">
          {(['MANGA', 'MANHWA', 'MANHUA', 'COMIC'] as ContentType[]).map((t) => (
            <button key={t} className={cx('chip', type === t && 'chip-active')} onClick={() => setType(t)}>{typeLabel[t]}</button>
          ))}
        </div>
        <div className="flex gap-2">
          <input className="input font-mono text-xs" value={path} onChange={(e) => setPath(e.target.value)} placeholder="/mnt/HD2/Mangas" />
          <button className="btn-secondary shrink-0" onClick={() => setPicker(true)}>Procurar…</button>
        </div>
        {result && (
          <div className="rounded-lg bg-ink-800 p-3 text-sm">
            <p>Séries: {result.seriesFound} ({result.seriesCreated} nova(s)) · Capítulos adicionados: {result.chaptersAdded} · Já existentes: {result.chaptersSkipped}</p>
            {result.errors.map((e) => <p key={e} className="mt-1 text-red-300">{e}</p>)}
          </div>
        )}
        <div className="flex justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Fechar</button>
          <button className="btn-primary" disabled={!path || mutation.isPending} onClick={() => mutation.mutate()}>
            {mutation.isPending ? <Spinner className="h-4 w-4 text-white" /> : <FolderInput className="h-4 w-4" />} Importar
          </button>
        </div>
      </div>
      <DirectoryPicker open={picker} initial={path || undefined} onClose={() => setPicker(false)} onSelect={(p) => { setPath(p); setPicker(false) }} />
    </Modal>
  )
}

function CategoriesModal({ open, onClose, categories }: { open: boolean; onClose: () => void; categories: Category[] }) {
  const [name, setName] = useState('')
  const qc = useQueryClient()
  const toast = useToast()
  const create = useMutation({
    mutationFn: () => api.post('/library/categories', { name }),
    onSuccess: () => { setName(''); qc.invalidateQueries({ queryKey: ['categories'] }) },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const remove = useMutation({
    mutationFn: (id: string) => api.delete(`/library/categories/${id}`),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['categories'] }); qc.invalidateQueries({ queryKey: ['library'] }) },
  })
  return (
    <Modal open={open} onClose={onClose} title="Categorias">
      <form className="mb-4 flex gap-2" onSubmit={(e) => { e.preventDefault(); if (name.trim()) create.mutate() }}>
        <input className="input" placeholder="Nova categoria" value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
        <button className="btn-primary shrink-0" disabled={!name.trim()}><Plus className="h-4 w-4" /> Criar</button>
      </form>
      <ul className="space-y-1">
        {categories.length === 0 && <li className="text-sm text-zinc-500">Nenhuma categoria criada.</li>}
        {categories.map((c) => (
          <li key={c.id} className="flex items-center justify-between rounded-lg px-3 py-2 hover:bg-ink-800">
            <span className="text-sm">{c.name}</span>
            <button className="btn-ghost p-1.5 text-red-300" onClick={() => remove.mutate(c.id)}><Trash2 className="h-4 w-4" /></button>
          </li>
        ))}
      </ul>
    </Modal>
  )
}
