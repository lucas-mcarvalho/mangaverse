import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ExternalLink, Plus, Search } from 'lucide-react'
import { Link } from 'react-router'
import { api, errorMessage } from '../api/client'
import type { ExtensionView, ProviderView, StoreView } from '../api/types'
import { useToast } from './Toast'
import { Badge, SectionTitle, Spinner } from './ui'

const HQ_NOW_PACKAGE = 'eu.kanade.tachiyomi.extension.pt.hqnow'
const KEIYOUSHI = 'https://raw.githubusercontent.com/keiyoushi/extensions/repo/index.min.json'

export function BrazilianComicsSources({ providers, online }: { providers: ProviderView[]; online: boolean }) {
  const qc = useQueryClient()
  const toast = useToast()
  const hqNow = providers.find((p) => p.name.toLowerCase().replace(/[^a-z0-9]/g, '') === 'hqnow')
  const ready = hqNow?.enabled && hqNow.supportsSearch && hqNow.defaultContentType === 'COMIC'
  const configure = useMutation({
    mutationFn: async () => {
      let source = hqNow
      if (!source || !source.supportsSearch) {
        const stores = (await api.get<StoreView[]>('/sources/suwayomi/stores')).data
        if (!stores.some((store) => store.indexUrl === KEIYOUSHI)) {
          await api.post('/sources/suwayomi/stores', { indexUrl: KEIYOUSHI })
        }
        const extensions = (await api.post<ExtensionView[]>('/sources/suwayomi/extensions/refresh')).data
        const extension = extensions.find((item) => item.pkgName === HQ_NOW_PACKAGE)
        if (!extension || extension.obsolete) {
          throw new Error('HQ Now! não está disponível no catálogo de extensões. Tente atualizar a lista mais tarde.')
        }
        if (!extension.installed || extension.hasUpdate) {
          await api.post(`/sources/suwayomi/extensions/${HQ_NOW_PACKAGE}/${extension.installed ? 'update' : 'install'}`)
        }
        const synced = (await api.post<ProviderView[]>('/sources/suwayomi/sync')).data
        source = synced.find((p) => p.name.toLowerCase().replace(/[^a-z0-9]/g, '') === 'hqnow')
      }
      if (!source?.supportsSearch) throw new Error('HQ Now! ainda não foi sincronizado. Tente novamente.')
      await api.patch(`/providers/${source.id}`, { enabled: true, defaultContentType: 'COMIC', priority: 15 })
    },
    onSuccess: () => toast.success('HQ Now! habilitado para pesquisar HQs em português.'),
    onError: (error) => toast.error(errorMessage(error)),
    onSettled: () => {
      qc.invalidateQueries({ queryKey: ['providers'] })
      qc.invalidateQueries({ queryKey: ['extensions'] })
      qc.invalidateQueries({ queryKey: ['stores'] })
      qc.invalidateQueries({ queryKey: ['library'] })
      qc.removeQueries({ queryKey: ['search'] })
      qc.removeQueries({ queryKey: ['explore'] })
    },
  })

  return (
    <section className="space-y-4">
      <SectionTitle>HQs em português · comunidades de fãs</SectionTitle>
      <div className="grid gap-3 md:grid-cols-2">
        <div className="card space-y-3 p-4">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-semibold">HQ Now!</h3>
            <Badge tone="blue">PT-BR · HQ</Badge>
            <Badge tone={ready ? 'green' : 'zinc'}>{ready ? 'Habilitada' : 'Configurar'}</Badge>
          </div>
          <p className="text-sm text-zinc-400">Pesquise as traduções de fãs e abra as edições no MangaVerse pela extensão HQ Now!. A leitura depende da disponibilidade da fonte.</p>
          {ready && hqNow && online ? (
            <Link className="btn-primary" to={`/pesquisa?tipos=COMIC&fonte=${encodeURIComponent(hqNow.code)}`}>
              <Search className="h-4 w-4" /> Pesquisar HQs
            </Link>
          ) : (
            <button className="btn-primary" disabled={!online || configure.isPending} onClick={() => configure.mutate()}>
              {configure.isPending ? <Spinner className="h-4 w-4" /> : <Plus className="h-4 w-4" />}
              {hqNow?.supportsSearch ? 'Habilitar HQ Now!' : 'Instalar HQ Now!'}
            </button>
          )}
          {!online && <p className="text-xs text-zinc-500">Conecte o Suwayomi para configurar e pesquisar nesta fonte.</p>}
        </div>
        <div className="card space-y-3 p-4">
          <div className="flex flex-wrap items-center gap-2">
            <h3 className="font-semibold">Só Quadrinhos</h3>
            <Badge tone="blue">PT-BR · HQ</Badge>
            <Badge tone="amber">Acesso externo</Badge>
          </div>
          <p className="text-sm text-zinc-400">Comunidade de scans e traduções de fãs. Consulte o acervo no site; esta fonte ainda não participa da pesquisa, leitura ou download do MangaVerse.</p>
          <a className="btn-secondary" href="https://site.soquadrinhos.com/" target="_blank" rel="noopener noreferrer">
            <ExternalLink className="h-4 w-4" /> Abrir Só Quadrinhos
          </a>
          <p className="text-xs text-zinc-500">Para ler arquivos que você já baixou, use Minha Biblioteca → Importar arquivos locais.</p>
        </div>
      </div>
    </section>
  )
}
