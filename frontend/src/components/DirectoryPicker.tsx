import { useQuery } from '@tanstack/react-query'
import { ArrowUp, Folder, HardDrive } from 'lucide-react'
import { useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { DirectoryListing } from '../api/types'
import { formatBytes } from '../lib/format'
import { Modal } from './Modal'
import { Spinner } from './ui'

/** Navegador de pastas restrito aos diretórios permitidos no servidor. */
export function DirectoryPicker({ open, initial, onClose, onSelect, title = 'Escolher pasta' }: {
  open: boolean; initial?: string; onClose: () => void; onSelect: (path: string) => void; title?: string
}) {
  const [path, setPath] = useState<string | undefined>(initial)
  const { data, isLoading, error } = useQuery({
    queryKey: ['browse', path ?? ''],
    queryFn: async () => (await api.get<DirectoryListing>('/library/browse', { params: { path } })).data,
    enabled: open,
    retry: false,
  })
  return (
    <Modal open={open} onClose={onClose} title={title}>
      <div className="mb-3 flex items-center gap-2">
        <button className="btn-secondary px-2.5" disabled={!data?.parent && !data?.path} onClick={() => setPath(data?.parent ?? undefined)} title="Subir">
          <ArrowUp className="h-4 w-4" />
        </button>
        <div className="input truncate font-mono text-xs">{data?.path ?? 'Diretórios permitidos'}</div>
      </div>
      {isLoading && <div className="flex justify-center p-6"><Spinner /></div>}
      {error && <p className="text-sm text-red-300">{errorMessage(error)}</p>}
      {data && (
        <ul className="max-h-80 space-y-1 overflow-y-auto">
          {data.directories.length === 0 && <li className="p-3 text-sm text-zinc-500">Nenhuma subpasta.</li>}
          {data.directories.map((d) => (
            <li key={d}>
              <button className="flex w-full items-center gap-2 rounded-lg px-3 py-2 text-left text-sm hover:bg-ink-700" onClick={() => setPath(d)}>
                {data.path ? <Folder className="h-4 w-4 text-accent-400" /> : <HardDrive className="h-4 w-4 text-accent-400" />}
                <span className="truncate">{data.path ? d.slice(data.path.length).replace(/^[\\/]/, '') : d}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-4 flex items-center justify-between gap-3 border-t border-ink-700 pt-4">
        <span className="text-xs text-zinc-500">{data?.freeBytes != null ? `Livre: ${formatBytes(data.freeBytes)}` : ''}</span>
        <div className="flex gap-2">
          <button className="btn-ghost" onClick={onClose}>Cancelar</button>
          <button className="btn-primary" disabled={!data?.path} onClick={() => data?.path && onSelect(data.path)}>Usar esta pasta</button>
        </div>
      </div>
    </Modal>
  )
}
