import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Activity, Download, Plus, RefreshCw, Search, Trash2, Upload, Wifi, WifiOff } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { ExtensionView, ProviderView, StoreView, SuwayomiStatus } from '../api/types'
import { useToast } from '../components/Toast'
import { Badge, Cover, EmptyState, ErrorState, PageLoader, SectionTitle, Spinner, Toggle } from '../components/ui'
import { cx, languageLabel, timeAgo } from '../lib/format'

const KEIYOUSHI = 'https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json'

const statusTone: Record<string, 'green' | 'red' | 'amber' | 'zinc'> = { ONLINE: 'green', OFFLINE: 'red', ERROR: 'red', NOT_CONFIGURED: 'amber', UNKNOWN: 'zinc' }
const statusText: Record<string, string> = { ONLINE: 'Online', OFFLINE: 'Indisponível', ERROR: 'Erro', NOT_CONFIGURED: 'Não configurado', UNKNOWN: 'Não verificado' }

export default function SourcesPage() {
  const qc = useQueryClient()
  const toast = useToast()
  const onError = (e: unknown) => toast.error(errorMessage(e))
  const providers = useQuery({ queryKey: ['providers'], queryFn: async () => (await api.get<ProviderView[]>('/providers')).data })
  const suwayomi = useQuery({ queryKey: ['suwayomi'], queryFn: async () => (await api.get<SuwayomiStatus>('/sources/suwayomi')).data })
  const health = useMutation({
    mutationFn: () => api.post('/providers/health'),
    onSuccess: () => { qc.invalidateQueries({ queryKey: ['providers'] }); qc.invalidateQueries({ queryKey: ['suwayomi'] }) }, onError,
  })
  const update = useMutation({
    mutationFn: ({ id, ...body }: { id: string; enabled?: boolean; priority?: number; defaultContentType?: string }) => api.patch(`/providers/${id}`, body),
    onSuccess: (_, v) => {
      qc.invalidateQueries({ queryKey: ['providers'] })
      // Fontes ligadas/desligadas ou com outro tipo mudam os resultados: descarta pesquisas em cache
      qc.removeQueries({ queryKey: ['search'] })
      qc.removeQueries({ queryKey: ['explore'] })
      if (v.defaultContentType) {
        qc.invalidateQueries({ queryKey: ['library'] })
        toast.success(v.defaultContentType === 'AUTO' ? 'Tipo da fonte: automático.' : 'Tipo da fonte atualizado; títulos já importados desta fonte foram ajustados.')
      }
    }, onError,
  })
  const sync = useMutation({
    mutationFn: () => api.post('/sources/suwayomi/sync'),
    onSuccess: () => {
      toast.success('Fontes sincronizadas com o Suwayomi.')
      qc.invalidateQueries({ queryKey: ['providers'] })
      qc.removeQueries({ queryKey: ['search'] })
      qc.removeQueries({ queryKey: ['explore'] })
    }, onError,
  })

  if (providers.isLoading) return <PageLoader />
  if (providers.error) return <ErrorState message={errorMessage(providers.error)} onRetry={providers.refetch} />
  const main = providers.data!.filter((p) => p.type !== 'SUWAYOMI_SOURCE')
  const sources = providers.data!.filter((p) => p.type === 'SUWAYOMI_SOURCE')
    .sort((a, b) => Number(b.language === 'pt-br') - Number(a.language === 'pt-br') || a.priority - b.priority)

  return (
    <div className="space-y-10">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">Fontes e extensões</h1>
          <p className="text-sm text-zinc-400">Provedores usados na pesquisa global. As extensões rodam no Suwayomi; o MangaVerse apenas as consulta.</p>
        </div>
        <button className="btn-secondary" disabled={health.isPending} onClick={() => health.mutate()}>
          {health.isPending ? <Spinner className="h-4 w-4" /> : <Activity className="h-4 w-4" />} Verificar disponibilidade
        </button>
      </div>

      <section>
        <SectionTitle>Provedores</SectionTitle>
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          {main.map((p) => (
            <div key={p.id} className="card space-y-3 p-4">
              <div className="flex items-center justify-between">
                <span className="font-semibold">{p.name}</span>
                <Badge tone={statusTone[p.lastStatus] ?? 'zinc'}>{statusText[p.lastStatus] ?? p.lastStatus}</Badge>
              </div>
              <div className="flex flex-wrap gap-1">
                {p.supportsSearch && <Badge>Pesquisa</Badge>}
                {p.supportsDownload && <Badge tone="green">Download</Badge>}
                {p.metadataOnly && <Badge tone="amber">Somente metadados</Badge>}
              </div>
              {p.lastError && <p className="text-xs text-amber-300">{p.lastError}</p>}
              <p className="text-xs text-zinc-500">Verificado {timeAgo(p.lastCheckedAt)}</p>
              {p.type !== 'LOCAL' && <Toggle checked={p.enabled} onChange={(v) => update.mutate({ id: p.id, enabled: v })} label="Usar na pesquisa" />}
            </div>
          ))}
        </div>
      </section>

      <section className="space-y-4">
        <SectionTitle action={
          <button className="btn-secondary" disabled={sync.isPending || !suwayomi.data?.online} onClick={() => sync.mutate()}>
            <RefreshCw className={cx('h-4 w-4', sync.isPending && 'animate-spin')} /> Sincronizar fontes
          </button>
        }>Suwayomi</SectionTitle>
        {suwayomi.data && (
          <div className={cx('card flex items-center gap-3 p-4', suwayomi.data.online ? 'border-emerald-500/30' : 'border-red-500/30')}>
            {suwayomi.data.online ? <Wifi className="h-5 w-5 text-emerald-400" /> : <WifiOff className="h-5 w-5 text-red-400" />}
            <div className="min-w-0 flex-1">
              <p className="text-sm font-medium">{suwayomi.data.message}</p>
              <p className="font-mono text-xs text-zinc-500">{suwayomi.data.baseUrl}</p>
            </div>
          </div>
        )}
        {suwayomi.data?.online && <Stores />}
        <div>
          <h3 className="mb-2 text-sm font-semibold text-zinc-300">Fontes instaladas ({sources.length})</h3>
          {sources.length === 0 ? (
            <EmptyState title="Nenhuma fonte do Suwayomi" description="Adicione um repositório, instale extensões abaixo e clique em Sincronizar fontes." />
          ) : (
            <div className="card divide-y divide-ink-700">
              {sources.map((p) => (
                <div key={p.id} className="flex items-center gap-3 px-4 py-2.5">
                  <Cover src={p.iconUrl} alt={p.name} className="h-8 w-8 shrink-0 rounded" />
                  <div className="min-w-0 flex-1">
                    <p className="flex items-center gap-2 truncate text-sm font-medium">{p.name}{p.defaultContentType === 'COMIC' && <Badge tone="blue">HQ</Badge>}</p>
                    <p className="text-xs text-zinc-500">{languageLabel(p.language)}{p.lastError ? ` · ${p.lastError}` : ''}</p>
                  </div>
                  <label className="flex items-center gap-1.5 text-xs text-zinc-400" title="Como os títulos desta fonte são classificados">Tipo
                    <select className="input w-auto px-2 py-1 text-xs" value={p.defaultContentType ?? 'AUTO'}
                      onChange={(e) => update.mutate({ id: p.id, defaultContentType: e.target.value })}>
                      <option value="AUTO">Automático</option><option value="MANGA">Mangá</option><option value="MANHWA">Manhwa</option>
                      <option value="MANHUA">Manhua</option><option value="COMIC">HQ</option>
                    </select>
                  </label>
                  <label className="flex items-center gap-1.5 text-xs text-zinc-400">Prioridade
                    <input type="number" className="input w-16 px-2 py-1 text-xs" defaultValue={p.priority} min={0} max={1000}
                      onBlur={(e) => Number(e.target.value) !== p.priority && update.mutate({ id: p.id, priority: Number(e.target.value) })} />
                  </label>
                  {p.enabled && p.supportsSearch && (
                    <Link className="btn-ghost px-2 py-1.5" to={`/pesquisa?fonte=${encodeURIComponent(p.code)}`} title={`Pesquisar em ${p.name}`} aria-label={`Pesquisar em ${p.name}`}>
                      <Search className="h-4 w-4" />
                    </Link>
                  )}
                  <Toggle checked={p.enabled} onChange={(v) => update.mutate({ id: p.id, enabled: v })} />
                </div>
              ))}
            </div>
          )}
        </div>
        {suwayomi.data?.online && <Extensions />}
      </section>
    </div>
  )
}

function Stores() {
  const qc = useQueryClient()
  const toast = useToast()
  const [url, setUrl] = useState('')
  const { data } = useQuery({ queryKey: ['stores'], queryFn: async () => (await api.get<StoreView[]>('/sources/suwayomi/stores')).data })
  const add = useMutation({
    mutationFn: (indexUrl: string) => api.post('/sources/suwayomi/stores', { indexUrl }),
    onSuccess: () => { setUrl(''); toast.success('Repositório adicionado.'); qc.invalidateQueries({ queryKey: ['stores'] }); qc.invalidateQueries({ queryKey: ['extensions'] }) },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const remove = useMutation({
    mutationFn: (indexUrl: string) => api.delete('/sources/suwayomi/stores', { params: { indexUrl } }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['stores'] }),
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <div className="card space-y-3 p-4">
      <h3 className="text-sm font-semibold text-zinc-300">Repositórios de extensões</h3>
      {data?.map((s) => (
        <div key={s.indexUrl} className="flex items-center gap-2 text-sm">
          <Badge tone="accent">{s.badgeLabel}</Badge><span className="font-medium">{s.name}</span>
          <span className="truncate font-mono text-xs text-zinc-500">{s.indexUrl}</span>
          <button className="btn-ghost ml-auto p-1.5 text-red-300" onClick={() => remove.mutate(s.indexUrl)}><Trash2 className="h-4 w-4" /></button>
        </div>
      ))}
      {data?.length === 0 && (
        <div className="flex flex-wrap items-center gap-3 rounded-lg bg-ink-800 p-3 text-sm">
          <span className="flex-1 text-zinc-300">Nenhum repositório configurado. O Keiyoushi mantém as extensões compatíveis com o Suwayomi.</span>
          <button className="btn-primary" disabled={add.isPending} onClick={() => add.mutate(KEIYOUSHI)}><Plus className="h-4 w-4" /> Adicionar Keiyoushi</button>
        </div>
      )}
      <form className="flex gap-2" onSubmit={(e) => { e.preventDefault(); if (url) add.mutate(url) }}>
        <input className="input font-mono text-xs" placeholder="https://…/index.min.json" value={url} onChange={(e) => setUrl(e.target.value)} />
        <button className="btn-secondary shrink-0" disabled={!url || add.isPending}><Plus className="h-4 w-4" /> Adicionar</button>
      </form>
    </div>
  )
}

function Extensions() {
  const qc = useQueryClient()
  const toast = useToast()
  const [lang, setLang] = useState('pt-br')
  const [installed, setInstalled] = useState<'all' | 'yes' | 'no'>('all')
  const [q, setQ] = useState('')
  const [busy, setBusy] = useState<string | null>(null)
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['extensions', lang, installed],
    queryFn: async () => (await api.get<ExtensionView[]>('/sources/suwayomi/extensions', {
      params: { lang: lang || undefined, installed: installed === 'all' ? undefined : installed === 'yes' },
    })).data,
  })
  const refresh = useMutation({
    mutationFn: () => api.post('/sources/suwayomi/extensions/refresh'),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['extensions'] }),
    onError: (e) => toast.error(errorMessage(e)),
  })
  const change = useMutation({
    mutationFn: ({ pkg, op }: { pkg: string; op: 'install' | 'uninstall' | 'update' }) => { setBusy(pkg); return api.post(`/sources/suwayomi/extensions/${pkg}/${op}`) },
    onSuccess: (_, v) => {
      toast.success(v.op === 'install' ? 'Extensão instalada e fontes sincronizadas.' : v.op === 'uninstall' ? 'Extensão removida.' : 'Extensão atualizada.')
      qc.invalidateQueries({ queryKey: ['extensions'] })
      qc.invalidateQueries({ queryKey: ['providers'] })
      qc.removeQueries({ queryKey: ['search'] })
      qc.removeQueries({ queryKey: ['explore'] })
    },
    onError: (e) => toast.error(errorMessage(e)),
    onSettled: () => setBusy(null),
  })
  const list = (data ?? []).filter((e) => !q || e.name.toLowerCase().includes(q.toLowerCase()))

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <h3 className="mr-auto text-sm font-semibold text-zinc-300">Extensões disponíveis</h3>
        <div className="relative">
          <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
          <input className="input w-48 py-1.5 pl-8 text-xs" placeholder="Filtrar" value={q} onChange={(e) => setQ(e.target.value)} />
        </div>
        <select className="input w-auto py-1.5 text-xs" value={lang} onChange={(e) => setLang(e.target.value)}>
          <option value="pt-br">Português (BR)</option><option value="all">Multi-idioma (all)</option><option value="en">Inglês</option>
          <option value="es">Espanhol</option><option value="">Todos os idiomas</option>
        </select>
        <select className="input w-auto py-1.5 text-xs" value={installed} onChange={(e) => setInstalled(e.target.value as typeof installed)}>
          <option value="all">Todas</option><option value="yes">Instaladas</option><option value="no">Não instaladas</option>
        </select>
        <button className="btn-secondary py-1.5" disabled={refresh.isPending} onClick={() => refresh.mutate()}><RefreshCw className={cx('h-4 w-4', refresh.isPending && 'animate-spin')} /></button>
      </div>
      {isLoading ? <PageLoader /> : error ? <ErrorState message={errorMessage(error)} onRetry={refetch} /> : list.length === 0 ? (
        <EmptyState title="Nenhuma extensão" description={lang ? 'Nenhuma extensão neste idioma com esse nome. Muitas fontes de HQ, como Read Comics Online, são em inglês.' : 'Adicione um repositório de extensões.'}
          action={lang ? <button className="btn-secondary" onClick={() => setLang('')}>Procurar em todos os idiomas</button> : undefined} />
      ) : (
        <div className="card max-h-[600px] divide-y divide-ink-700 overflow-y-auto">
          {list.map((e) => (
            <div key={e.pkgName} className="flex items-center gap-3 px-4 py-2.5">
              <Cover src={e.iconUrl} alt={e.name} className="h-8 w-8 shrink-0 rounded" />
              <div className="min-w-0 flex-1">
                <p className="flex items-center gap-2 truncate text-sm font-medium">{e.name}
                  {e.contentWarning === 'NSFW' && <Badge tone="red">18+</Badge>}
                  {e.obsolete && <Badge tone="amber">Obsoleta</Badge>}
                </p>
                <p className="text-xs text-zinc-500">{e.lang} · v{e.versionName}</p>
              </div>
              {busy === e.pkgName ? <Spinner /> : e.installed ? (
                <div className="flex gap-1">
                  {e.hasUpdate && <button className="btn-primary py-1.5" onClick={() => change.mutate({ pkg: e.pkgName, op: 'update' })}><Upload className="h-4 w-4" /> Atualizar</button>}
                  <button className="btn-ghost py-1.5 text-red-300" onClick={() => change.mutate({ pkg: e.pkgName, op: 'uninstall' })}><Trash2 className="h-4 w-4" /></button>
                </div>
              ) : (
                <button className="btn-secondary py-1.5" onClick={() => change.mutate({ pkg: e.pkgName, op: 'install' })}><Download className="h-4 w-4" /> Instalar</button>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
