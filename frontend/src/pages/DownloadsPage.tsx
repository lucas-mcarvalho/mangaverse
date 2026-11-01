import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Ban, Pause, Play, RotateCcw, ShieldCheck, Trash2 } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { DownloadStatus, DownloadSummary, DownloadView } from '../api/types'
import { Modal } from '../components/Modal'
import { useToast } from '../components/Toast'
import { Badge, Cover, EmptyState, ErrorState, PageLoader, ProgressBar, Spinner } from '../components/ui'
import { cx, downloadStatusLabel, formatBytes, timeAgo } from '../lib/format'

const tone: Record<DownloadStatus, 'accent' | 'green' | 'red' | 'zinc' | 'amber'> = {
  QUEUED: 'zinc', DOWNLOADING: 'accent', PAUSED: 'amber', COMPLETED: 'green', FAILED: 'red', CANCELLED: 'zinc',
}

interface IntegrityReport { checked: number; ok: number; issues: { chapterId: string; path: string; problem: string }[] }

export default function DownloadsPage() {
  const qc = useQueryClient()
  const toast = useToast()
  const [report, setReport] = useState<IntegrityReport | null>(null)
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: ['downloads'],
    queryFn: async () => (await api.get<DownloadView[]>('/downloads')).data,
    refetchInterval: (q) => q.state.data?.some((d) => d.status === 'DOWNLOADING' || d.status === 'QUEUED') ? 1500 : 8000,
  })
  const { data: summary } = useQuery({
    queryKey: ['downloads', 'summary'],
    queryFn: async () => (await api.get<DownloadSummary>('/downloads/summary')).data,
    refetchInterval: 3000,
  })
  const action = useMutation({
    mutationFn: ({ id, op }: { id: string; op: string }) => api.post(`/downloads/${id}/${op}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['downloads'] }),
    onError: (e) => toast.error(errorMessage(e)),
  })
  const clear = useMutation({
    mutationFn: async () => (await api.delete<{ removed: number }>('/downloads/finished')).data,
    onSuccess: (r) => { toast.success(`${r.removed} registro(s) removido(s) do histórico.`); qc.invalidateQueries({ queryKey: ['downloads'] }) },
  })
  const verify = useMutation({
    mutationFn: async () => (await api.post<IntegrityReport>('/downloads/verify')).data,
    onSuccess: setReport,
    onError: (e) => toast.error(errorMessage(e)),
  })

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-white">Downloads</h1>
          <p className="text-sm text-zinc-400">
            {summary ? `${summary.downloading} baixando · ${summary.queued} na fila · ${summary.paused} pausado(s) · ${summary.failed} com falha · limite ${summary.maxConcurrent} simultâneo(s)` : 'Fila de downloads'}
          </p>
        </div>
        <div className="flex gap-2">
          <button className="btn-secondary" disabled={verify.isPending} onClick={() => verify.mutate()}>
            {verify.isPending ? <Spinner className="h-4 w-4" /> : <ShieldCheck className="h-4 w-4" />} Verificar integridade
          </button>
          <button className="btn-secondary" onClick={() => clear.mutate()}><Trash2 className="h-4 w-4" /> Limpar concluídos</button>
        </div>
      </div>

      {isLoading ? <PageLoader /> : error ? <ErrorState message={errorMessage(error)} onRetry={refetch} /> : !data?.length ? (
        <EmptyState title="Nenhum download" description="Abra um título da biblioteca e escolha capítulos para baixar. Somente fontes que permitem download aparecem com essa opção." />
      ) : (
        <div className="card divide-y divide-ink-700">
          {data.map((d) => (
            <div key={d.id} className="flex flex-col gap-3 p-4 sm:flex-row sm:items-center">
              <Link to={`/conteudo/${d.contentId}`} className="flex min-w-0 flex-1 items-center gap-3">
                <Cover src={d.coverUrl} alt={d.contentTitle} className="h-16 w-11 shrink-0 rounded" />
                <div className="min-w-0 flex-1 space-y-1.5">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="truncate font-medium text-zinc-100">{d.contentTitle}</span>
                    <span className="text-sm text-zinc-400">{d.chapterLabel}</span>
                    <Badge tone={tone[d.status]}>{d.status === 'DOWNLOADING' && <Spinner className="h-3 w-3" />}{downloadStatusLabel[d.status]}</Badge>
                    {d.autoTriggered && <Badge tone="blue">Automático</Badge>}
                  </div>
                  {(d.status === 'DOWNLOADING' || d.status === 'PAUSED' || d.status === 'QUEUED') && (
                    <ProgressBar value={d.progress} tone={d.status === 'PAUSED' ? 'zinc' : 'accent'} />
                  )}
                  <p className={cx('text-xs', d.status === 'FAILED' ? 'text-red-300' : 'text-zinc-500')}>
                    {d.status === 'FAILED' ? d.errorMessage : [
                      d.totalPages ? `${d.downloadedPages}/${d.totalPages} páginas` : null,
                      d.bytesDownloaded ? formatBytes(d.bytesDownloaded) : null,
                      d.status === 'DOWNLOADING' && d.speedBps ? `${formatBytes(d.speedBps)}/s` : null,
                      d.status === 'COMPLETED' && d.filePath ? d.filePath : null,
                      timeAgo(d.finishedAt ?? d.startedAt ?? d.createdAt),
                      d.attempts > 1 ? `${d.attempts} tentativas` : null,
                    ].filter(Boolean).join(' · ')}
                  </p>
                </div>
              </Link>
              <div className="flex shrink-0 gap-1">
                {(d.status === 'DOWNLOADING' || d.status === 'QUEUED') && <IconBtn title="Pausar" onClick={() => action.mutate({ id: d.id, op: 'pause' })}><Pause className="h-4 w-4" /></IconBtn>}
                {d.status === 'PAUSED' && <IconBtn title="Retomar" onClick={() => action.mutate({ id: d.id, op: 'resume' })}><Play className="h-4 w-4" /></IconBtn>}
                {(d.status === 'FAILED' || d.status === 'CANCELLED') && <IconBtn title="Tentar novamente" onClick={() => action.mutate({ id: d.id, op: 'retry' })}><RotateCcw className="h-4 w-4" /></IconBtn>}
                {(d.status === 'DOWNLOADING' || d.status === 'QUEUED' || d.status === 'PAUSED') && <IconBtn title="Cancelar" onClick={() => action.mutate({ id: d.id, op: 'cancel' })}><Ban className="h-4 w-4 text-red-300" /></IconBtn>}
              </div>
            </div>
          ))}
        </div>
      )}

      <Modal open={!!report} onClose={() => setReport(null)} title="Verificação de integridade">
        {report && (
          <div className="space-y-3 text-sm">
            <p>{report.checked} arquivo(s) verificado(s): <span className="text-emerald-300">{report.ok} íntegro(s)</span>, <span className={report.issues.length ? 'text-red-300' : ''}>{report.issues.length} com problema</span>.</p>
            <ul className="space-y-2">
              {report.issues.map((i) => (
                <li key={i.chapterId} className="rounded-lg bg-ink-800 p-2"><p className="font-mono text-xs text-zinc-400">{i.path}</p><p className="text-red-300">{i.problem}</p></li>
              ))}
            </ul>
          </div>
        )}
      </Modal>
    </div>
  )
}

function IconBtn({ title, onClick, children }: { title: string; onClick: () => void; children: React.ReactNode }) {
  return <button className="btn-ghost p-2" title={title} onClick={onClick}>{children}</button>
}
