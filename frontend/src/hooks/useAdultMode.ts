import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Settings } from '../api/types'

/** Tudo que muda quando o modo +18 liga/desliga (fontes, extensões, pesquisas, catálogo, biblioteca). */
const AFFECTED = ['providers', 'extensions', 'search', 'explore', 'catalog', 'library', 'dashboard']

/** Estado do modo +18 (desligado por padrão) e a ação de ligar/desligar, salva na hora. */
export function useAdultMode() {
  const qc = useQueryClient()
  const settings = useQuery({ queryKey: ['settings'], queryFn: async () => (await api.get<Settings>('/settings')).data })
  const change = useMutation({
    mutationFn: async (adultMode: boolean) => (await api.put<Settings>('/settings', { adultMode })).data,
    onSuccess: (s) => {
      qc.setQueryData(['settings'], (old: Settings | undefined) => (old ? { ...old, adultMode: s.adultMode, contentRatings: s.contentRatings } : s))
      AFFECTED.forEach((key) => qc.invalidateQueries({ queryKey: [key] }))
    },
  })
  return { adultMode: settings.data?.adultMode === true, loaded: settings.isSuccess, change }
}
