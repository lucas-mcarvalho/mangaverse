import { useEffect, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'

export function Modal({ open, onClose, title, children, wide }: { open: boolean; onClose: () => void; title: string; children: ReactNode; wide?: boolean }) {
  useEffect(() => {
    if (!open) return
    const onKey = (e: KeyboardEvent) => e.key === 'Escape' && onClose()
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [open, onClose])
  if (!open) return null
  // No body, fora da árvore de quem abriu: linhas animadas da lista criam um contexto de
  // empilhamento que prenderia a janela embaixo da barra fixa da página.
  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 backdrop-blur-sm animate-fade-in" onMouseDown={onClose}>
      <div className={`card max-h-[90vh] w-full overflow-hidden shadow-2xl ${wide ? 'max-w-3xl' : 'max-w-lg'}`} onMouseDown={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-ink-700 px-5 py-3">
          <h3 className="font-semibold text-zinc-100">{title}</h3>
          <button className="btn-ghost p-1.5" onClick={onClose} aria-label="Fechar"><X className="h-4 w-4" /></button>
        </div>
        <div className="max-h-[calc(90vh-56px)] overflow-y-auto p-5">{children}</div>
      </div>
    </div>,
    document.body,
  )
}
