import { ShieldAlert } from 'lucide-react'
import { useState } from 'react'
import { errorMessage } from '../api/client'
import { useAdultMode } from '../hooks/useAdultMode'
import { Modal } from './Modal'
import { useToast } from './Toast'
import { Spinner, Toggle } from './ui'

/**
 * Liga/desliga o modo +18. Ligar pede confirmação de idade; a mudança vale na hora para todas as telas.
 */
export function AdultModeToggle() {
  const toast = useToast()
  const { adultMode, loaded, change } = useAdultMode()
  const [confirm, setConfirm] = useState(false)
  const apply = (value: boolean) => change.mutate(value, {
    onSuccess: () => toast.success(value ? 'Modo +18 ligado.' : 'Modo +18 desligado: o conteúdo adulto ficou escondido.'),
    onError: (e) => toast.error(errorMessage(e)),
  })

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-4">
        <div>
          <p className="text-sm font-medium text-zinc-100">Modo +18 (conteúdo adulto)</p>
          <p className="text-xs text-zinc-500">
            {adultMode
              ? 'Ligado: fontes e extensões de hentai/conteúdo adulto aparecem, separadas das outras.'
              : 'Desligado: fontes e extensões de hentai/conteúdo adulto ficam fora da pesquisa, do Explorar, da biblioteca e da lista de extensões.'}
          </p>
        </div>
        {change.isPending ? <Spinner /> : (
          <Toggle checked={adultMode} disabled={!loaded} onChange={(v) => (v ? setConfirm(true) : apply(false))} />
        )}
      </div>
      <Modal open={confirm} onClose={() => setConfirm(false)} title="Ligar o modo +18?">
        <div className="space-y-4 text-sm text-zinc-300">
          <div className="flex gap-3">
            <ShieldAlert className="h-6 w-6 shrink-0 text-pink-accent" />
            <p>
              O modo +18 libera fontes e extensões de hentai e outros conteúdos sexualmente explícitos, além das classificações
              Erótico e Adulto da MangaDex. Tudo fica separado das fontes comuns e você pode desligar quando quiser.
            </p>
          </div>
          <p className="font-medium text-zinc-100">Confirme que você tem 18 anos ou mais.</p>
          <div className="flex justify-end gap-2">
            <button className="btn-secondary" onClick={() => setConfirm(false)}>Cancelar</button>
            <button className="btn-primary" onClick={() => { setConfirm(false); apply(true) }}>Tenho 18 anos ou mais</button>
          </div>
        </div>
      </Modal>
    </div>
  )
}
