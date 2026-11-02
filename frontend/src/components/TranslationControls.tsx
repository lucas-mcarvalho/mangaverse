import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpen, Languages, RotateCcw } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { TranslationJob } from '../api/types'
import { Modal } from './Modal'
import { useToast } from './Toast'
import { ProgressBar, Spinner } from './ui'

export function useTranslation(chapterId: string, enabled = true) {
  return useQuery({
    queryKey: ['translation', chapterId],
    queryFn: async () => (await api.get<TranslationJob>(`/translations/chapters/${chapterId}`)).data,
    enabled,
    refetchInterval: (query) => ['QUEUED', 'PROCESSING'].includes(query.state.data?.status ?? '') ? 2000 : false,
  })
}

export function TranslationControls({ chapterId, label }: { chapterId: string; label: string }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button className="btn-secondary px-2.5 py-1.5" onClick={() => setOpen(true)} title={`Preparar tradução de ${label}: inglês → português`}>
        <Languages className="h-4 w-4" /> <span className="hidden sm:inline">Traduzir</span>
      </button>
      {open && <TranslationModal chapterId={chapterId} label={label} onClose={() => setOpen(false)} />}
    </>
  )
}

function TranslationModal({ chapterId, label, onClose }: { chapterId: string; label: string; onClose: () => void }) {
  const qc = useQueryClient()
  const toast = useToast()
  const job = useTranslation(chapterId)
  const base = `/translations/chapters/${chapterId}`
  const [confirmRedo, setConfirmRedo] = useState(false)
  const change = useMutation({
    mutationFn: async (action: 'start' | 'cancel' | 'redo') => (action === 'start' ? await api.post<TranslationJob>(base)
      : action === 'redo' ? await api.post<TranslationJob>(`${base}/redo`) : await api.delete<TranslationJob>(base)).data,
    onSuccess: (result) => { setConfirmRedo(false); qc.setQueryData(['translation', chapterId], result) },
    onError: (error) => toast.error(errorMessage(error)),
  })
  const status = job.data?.status ?? 'NONE'
  const working = status === 'QUEUED' || status === 'PROCESSING'
  return (
    <Modal open onClose={onClose} title={`Traduzir ${label}`}>
      <div className="space-y-4">
        <p className="text-sm text-zinc-300">Prepare esta edição inteira de inglês para português antes de ler. A versão traduzida fica salva e o original é preservado.</p>
        <p className="text-xs text-zinc-400">Tradução automática local. O primeiro uso baixa o modelo; letras estilizadas e textos sobre desenhos podem ter erros. O tempo de preparação depende do seu computador.</p>
        {job.isLoading && <Spinner />}
        {job.error && <p role="alert" className="text-sm text-red-300">{errorMessage(job.error)} <button className="underline" onClick={() => job.refetch()}>Tentar novamente</button></p>}
        {job.data && status !== 'NONE' && (
          <div className="space-y-2" aria-live="polite">
            <p className="text-sm">{status === 'QUEUED' ? 'Na fila' : status === 'PROCESSING' ? 'Preparando tradução' : status === 'READY' ? 'Pronta para leitura' : status === 'CANCELLED' ? 'Cancelada · páginas prontas preservadas' : 'Falha na preparação'} · {job.data.completedPages} de {job.data.totalPages} páginas</p>
            <ProgressBar value={job.data.totalPages ? job.data.completedPages / job.data.totalPages * 100 : 0} />
            {job.data.error && <p role="alert" className="text-sm text-red-300">{job.data.error}</p>}
          </div>
        )}
        {working && <p className="text-xs text-zinc-400">Você pode fechar esta janela e acompanhar depois. O processamento continua em segundo plano.</p>}
        {confirmRedo && <p role="alert" className="text-sm text-amber-300">As páginas já traduzidas desta edição serão apagadas e a tradução começa do zero. Clique em <strong>Confirmar</strong> para continuar.</p>}
        <div className="flex flex-wrap justify-end gap-2">
          <button className="btn-ghost" onClick={onClose}>Fechar</button>
          {!working && status !== 'NONE' && job.data && (
            <button className="btn-secondary" disabled={change.isPending}
              title="Apaga as páginas traduzidas desta edição e traduz tudo de novo com a versão atual do tradutor"
              onClick={() => confirmRedo ? change.mutate('redo') : setConfirmRedo(true)}>
              <RotateCcw className="h-4 w-4" /> {confirmRedo ? 'Confirmar' : 'Traduzir novamente'}
            </button>
          )}
          {status === 'READY' ? (
            <Link className="btn-primary" to={`/ler/${chapterId}?traducao=pt-br`} onClick={onClose}><BookOpen className="h-4 w-4" /> Ler em português</Link>
          ) : working ? (
            <button className="btn-secondary" disabled={change.isPending} onClick={() => change.mutate('cancel')}>Cancelar preparação</button>
          ) : (
            <button className="btn-primary" disabled={change.isPending || job.isLoading || !!job.error} onClick={() => change.mutate('start')}>
              {change.isPending ? <Spinner className="h-4 w-4" /> : <Languages className="h-4 w-4" />}
              {status === 'FAILED' || status === 'CANCELLED' ? 'Retomar tradução' : 'Preparar tradução'}
            </button>
          )}
        </div>
      </div>
    </Modal>
  )
}
