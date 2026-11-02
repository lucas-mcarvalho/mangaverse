import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Cpu, FolderOpen, Radar, Save } from 'lucide-react'
import { useEffect, useState, type ReactNode } from 'react'
import { api, errorMessage } from '../api/client'
import type { MonitorStatus, Settings } from '../api/types'
import { DirectoryPicker } from '../components/DirectoryPicker'
import { useToast } from '../components/Toast'
import { Badge, ErrorState, PageLoader, Spinner, Toggle } from '../components/ui'
import { cx, timeAgo } from '../lib/format'

const RATINGS = [
  { id: 'safe', label: 'Livre' }, { id: 'suggestive', label: 'Sugestivo' }, { id: 'erotica', label: 'Erótico' }, { id: 'pornographic', label: 'Adulto' },
]

export default function SettingsPage() {
  const qc = useQueryClient()
  const toast = useToast()
  const { data, isLoading, error, refetch } = useQuery({ queryKey: ['settings'], queryFn: async () => (await api.get<Settings>('/settings')).data })
  const monitor = useQuery({ queryKey: ['monitor'], queryFn: async () => (await api.get<MonitorStatus>('/monitor')).data, refetchInterval: 10_000 })
  const [form, setForm] = useState<Settings | null>(null)
  const [languages, setLanguages] = useState('')
  const [picker, setPicker] = useState<'manga' | 'comic' | null>(null)
  useEffect(() => {
    if (data) {
      setForm(data)
      setLanguages(data.languages.join(', '))
    }
  }, [data])

  const save = useMutation({
    mutationFn: async () => (await api.put<Settings>('/settings', {
      mangaLibraryPath: form!.mangaLibraryPath, comicLibraryPath: form!.comicLibraryPath,
      maxConcurrentDownloads: form!.maxConcurrentDownloads, autoDownloadEnabled: form!.autoDownloadEnabled,
      monitorEnabled: form!.monitorEnabled, monitorIntervalMinutes: form!.monitorIntervalMinutes,
      languages: languages.split(',').map((l) => l.trim()).filter(Boolean), contentRatings: form!.contentRatings,
      translationThreads: form!.translationThreads,
    })).data,
    onSuccess: (s) => { qc.setQueryData(['settings'], s); qc.invalidateQueries({ queryKey: ['monitor'] }); toast.success('Configurações salvas.') },
    onError: (e) => toast.error(errorMessage(e)),
  })
  const runMonitor = useMutation({
    mutationFn: () => api.post('/monitor/run'),
    onSuccess: () => { toast.info('Verificação iniciada em segundo plano.'); qc.invalidateQueries({ queryKey: ['monitor'] }) },
    onError: (e) => toast.error(errorMessage(e)),
  })

  if (isLoading || !form) return error ? <ErrorState message={errorMessage(error)} onRetry={refetch} /> : <PageLoader />
  const set = (patch: Partial<Settings>) => setForm({ ...form, ...patch })

  return (
    <div className="max-w-4xl space-y-6">
      <div className="flex items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">Configurações</h1>
          <p className="text-sm text-zinc-400">Armazenamento, downloads, monitoramento, tradução e preferências de conteúdo.</p>
        </div>
        <button className="btn-primary" disabled={save.isPending} onClick={() => save.mutate()}>
          {save.isPending ? <Spinner className="h-4 w-4 text-white" /> : <Save className="h-4 w-4" />} Salvar
        </button>
      </div>

      <Section title="Armazenamento" description="Pastas onde os downloads são salvos. Só é possível escolher pastas dentro dos diretórios permitidos (MANGAVERSE_STORAGE_ALLOWED_ROOTS).">
        <PathField label="Mangás, manhwas e manhuas" value={form.mangaLibraryPath} onChange={(v) => set({ mangaLibraryPath: v })} onBrowse={() => setPicker('manga')} />
        <PathField label="HQs" value={form.comicLibraryPath} onChange={(v) => set({ comicLibraryPath: v })} onBrowse={() => setPicker('comic')} />
        <div className="flex flex-wrap gap-1.5 text-xs text-zinc-400">Diretórios permitidos: {form.allowedRoots.map((r) => <Badge key={r} className="font-mono">{r}</Badge>)}</div>
        <p className="text-xs text-zinc-500">Leitura de CBR: {form.cbrSupported ? <span className="text-emerald-300">disponível via {form.cbrTool}</span> : <span className="text-amber-300">instale bsdtar (libarchive), unrar ou 7z</span>}</p>
      </Section>

      <Section title="Downloads">
        <label className="flex items-center justify-between gap-4 text-sm">
          <span>Downloads simultâneos</span>
          <input type="number" min={1} max={6} className="input w-24" value={form.maxConcurrentDownloads} onChange={(e) => set({ maxConcurrentDownloads: Number(e.target.value) })} />
        </label>
        <Toggle checked={form.autoDownloadEnabled} onChange={(v) => set({ autoDownloadEnabled: v })} label="Permitir downloads automáticos de novos capítulos (por título, quando ativado na biblioteca)" />
      </Section>

      <Section title="Monitoramento de novos capítulos" description="As fontes são consultadas no intervalo definido, respeitando limites de requisições. Intervalo mínimo: 30 minutos.">
        <Toggle checked={form.monitorEnabled} onChange={(v) => set({ monitorEnabled: v })} label="Monitoramento automático ativado" />
        <label className="flex items-center justify-between gap-4 text-sm">
          <span>Verificar a cada (minutos)</span>
          <input type="number" min={30} max={10080} step={30} className="input w-28" value={form.monitorIntervalMinutes} onChange={(e) => set({ monitorIntervalMinutes: Number(e.target.value) })} />
        </label>
        <div className="flex items-center justify-between gap-3 rounded-lg bg-ink-800 p-3 text-sm">
          <span className="text-zinc-400">Última verificação: {timeAgo(monitor.data?.lastRunAt)} {monitor.data?.running && <Badge tone="accent"><Spinner className="h-3 w-3" /> em andamento</Badge>}</span>
          <button className="btn-secondary" disabled={runMonitor.isPending || monitor.data?.running} onClick={() => runMonitor.mutate()}><Radar className="h-4 w-4" /> Verificar agora</button>
        </div>
      </Section>

      <Section title="Idiomas e conteúdo">
        <label className="block space-y-1.5 text-sm">
          <span>Idiomas de tradução (em ordem de prioridade)</span>
          <input className="input font-mono" value={languages} onChange={(e) => setLanguages(e.target.value)} placeholder="pt-br, en" />
          <span className="text-xs text-zinc-500">O primeiro idioma é priorizado na pesquisa, nos downloads automáticos e na escolha de capítulos.</span>
        </label>
        <div className="space-y-1.5 text-sm">
          <span>Classificação de conteúdo (MangaDex)</span>
          <div className="flex flex-wrap gap-2">
            {RATINGS.map((r) => {
              const on = form.contentRatings.includes(r.id)
              return <button key={r.id} className={cx('chip', on && 'chip-active')} onClick={() => set({ contentRatings: on ? form.contentRatings.filter((x) => x !== r.id) : [...form.contentRatings, r.id] })}>{r.label}</button>
            })}
          </div>
        </div>
      </Section>

      <Section title="Tradução" description="Mais núcleos deixam cada página mais rápida, mas o computador fica mais ocupado enquanto a tradução roda. Vale a partir da próxima página processada.">
        <label className="block space-y-2 text-sm">
          <span className="flex items-center justify-between gap-4">
            <span className="flex items-center gap-2"><Cpu className="h-4 w-4 text-zinc-400" /> Núcleos da CPU para o tradutor</span>
            <span className="font-mono text-zinc-200">{form.translationThreads} de {form.availableCpus}</span>
          </span>
          <input type="range" min={1} max={form.availableCpus} step={1} className="w-full accent-accent-500" value={form.translationThreads}
            onChange={(e) => set({ translationThreads: Number(e.target.value) })} />
          <span className="text-xs text-zinc-500">
            {form.translationThreads >= form.availableCpus ? 'Todos os núcleos: tradução mais rápida, mas outros programas ficam lentos durante o processamento.'
              : form.translationThreads > form.availableCpus / 2 ? 'Mais da metade dos núcleos: rápido, com o computador ainda utilizável.'
                : 'Uso moderado: deixa a maior parte do processador livre para outras tarefas.'}
          </span>
        </label>
      </Section>

      <Section title="Integrações">
        <p className="text-sm">Comic Vine: {form.comicVineConfigured ? <Badge tone="green">configurado</Badge> : <Badge tone="amber">defina COMICVINE_API_KEY no .env</Badge>}</p>
        <p className="text-sm">Suwayomi: <span className="font-mono text-xs text-zinc-400">{form.suwayomiUrl}</span></p>
      </Section>

      <PasswordSection />

      <DirectoryPicker open={picker != null} initial={picker === 'comic' ? form.comicLibraryPath : form.mangaLibraryPath} onClose={() => setPicker(null)}
        onSelect={(p) => { set(picker === 'comic' ? { comicLibraryPath: p } : { mangaLibraryPath: p }); setPicker(null) }} />
    </div>
  )
}

function Section({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return (
    <section className="card space-y-4 p-5">
      <div>
        <h2 className="font-semibold text-zinc-100">{title}</h2>
        {description && <p className="mt-0.5 text-xs text-zinc-500">{description}</p>}
      </div>
      {children}
    </section>
  )
}

function PathField({ label, value, onChange, onBrowse }: { label: string; value: string; onChange: (v: string) => void; onBrowse: () => void }) {
  return (
    <label className="block space-y-1.5 text-sm">
      <span>{label}</span>
      <div className="flex gap-2">
        <input className="input font-mono text-xs" value={value} onChange={(e) => onChange(e.target.value)} />
        <button type="button" className="btn-secondary shrink-0" onClick={onBrowse}><FolderOpen className="h-4 w-4" /> Procurar</button>
      </div>
    </label>
  )
}

function PasswordSection() {
  const toast = useToast()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const change = useMutation({
    mutationFn: () => api.post('/auth/password', { currentPassword: current, newPassword: next }),
    onSuccess: () => { setCurrent(''); setNext(''); toast.success('Senha alterada.') },
    onError: (e) => toast.error(errorMessage(e)),
  })
  return (
    <Section title="Alterar senha">
      <form className="grid gap-3 sm:grid-cols-[1fr_1fr_auto]" onSubmit={(e) => { e.preventDefault(); change.mutate() }}>
        <input className="input" type="password" placeholder="Senha atual" value={current} onChange={(e) => setCurrent(e.target.value)} autoComplete="current-password" />
        <input className="input" type="password" placeholder="Nova senha (mín. 8)" value={next} onChange={(e) => setNext(e.target.value)} minLength={8} autoComplete="new-password" />
        <button className="btn-secondary" disabled={!current || next.length < 8 || change.isPending}>Alterar</button>
      </form>
    </Section>
  )
}
