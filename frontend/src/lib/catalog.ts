import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { CatalogKind, CatalogSource, Genre, RemoteTitle } from '../api/types'

export const titleLink = (t: RemoteTitle) => `/titulo/${encodeURIComponent(t.providerCode)}/${encodeURIComponent(t.externalId)}`

export const listLink = (source: string, kind: CatalogKind, genre?: Genre) =>
  `/explorar/lista?fonte=${encodeURIComponent(source)}&tipo=${kind}` + (genre ? `&genero=${encodeURIComponent(genre.id)}&nome=${encodeURIComponent(genre.name)}` : '')

export function useCatalogSources() {
  return useQuery({ queryKey: ['catalog', 'sources'], queryFn: async () => (await api.get<CatalogSource[]>('/catalog/sources')).data })
}

export function useGenres(source: string | undefined) {
  return useQuery({
    queryKey: ['catalog', 'genres', source],
    queryFn: async () => (await api.get<Genre[]>('/catalog/genres', { params: { provider: source } })).data,
    enabled: !!source, staleTime: 30 * 60_000,
  })
}
