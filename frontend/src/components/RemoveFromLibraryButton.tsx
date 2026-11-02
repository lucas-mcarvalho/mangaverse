import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Trash2 } from 'lucide-react'
import { api, errorMessage } from '../api/client'
import { useToast } from './Toast'
import { Spinner } from './ui'

export function RemoveFromLibraryButton({ entryId, contentId, title }: { entryId: string; contentId: string; title: string }) {
  const qc = useQueryClient()
  const toast = useToast()
  const remove = useMutation({
    mutationFn: () => api.delete(`/library/${entryId}`),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['library'] })
      qc.invalidateQueries({ queryKey: ['dashboard'] })
      qc.invalidateQueries({ queryKey: ['content', contentId] })
      qc.invalidateQueries({ queryKey: ['continue', contentId] })
      toast.success(`${title} removido da biblioteca. Arquivos baixados e histórico preservados.`)
    },
    onError: (error) => toast.error(errorMessage(error)),
  })
  return (
    <button className="btn-ghost shrink-0 px-2 py-1.5 text-xs text-red-300" disabled={remove.isPending}
      onClick={() => remove.mutate()} aria-label={`Remover ${title} da biblioteca`} title="Remover da biblioteca e manter arquivos e histórico">
      {remove.isPending ? <Spinner className="h-4 w-4" /> : <Trash2 className="h-4 w-4" />} Remover
    </button>
  )
}
