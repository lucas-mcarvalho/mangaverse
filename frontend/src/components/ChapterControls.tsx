import { ArrowDownWideNarrow, ArrowUpNarrowWide, Search, X } from 'lucide-react'
import { useState } from 'react'

const SORT_KEY = 'mangaverse:chapters:desc'

function readSort(): boolean {
  try {
    return localStorage.getItem(SORT_KEY) === '1'
  } catch {
    return false
  }
}

/** Busca e ordem da lista de capítulos; a ordem escolhida é lembrada entre títulos. */
export function useChapterControls() {
  const [query, setQuery] = useState('')
  const [desc, setDescState] = useState(readSort)
  const setDesc = (value: boolean) => {
    setDescState(value)
    try {
      localStorage.setItem(SORT_KEY, value ? '1' : '0')
    } catch {
      // preferência só nesta sessão
    }
  }
  return { query, setQuery, desc, setDesc }
}

const normalize = (s: string) => s.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim()

/**
 * Número digitado ("105", "10.5", "#105", "cap 105") busca pelo início do número do capítulo;
 * texto busca no título, no grupo de tradução e no rótulo.
 */
export function matchesChapter(
  c: { numberLabel: string | null; title: string | null; scanlator: string | null; label?: string },
  rawQuery: string,
): boolean {
  const q = normalize(rawQuery)
  if (!q) return true
  const numeric = q.replace(/^(cap(itulo)?|ch(apter)?|ed(icao)?|#)\s*\.?\s*/, '')
  if (/^\d+([.,]\d+)?$/.test(numeric)) {
    return (c.numberLabel ?? '').startsWith(numeric.replace(',', '.'))
  }
  return [c.title, c.scanlator, c.label].some((v) => v && normalize(v).includes(q))
}

export function ChapterControls({ query, setQuery, desc, setDesc, comic }: {
  query: string; setQuery: (v: string) => void; desc: boolean; setDesc: (v: boolean) => void; comic?: boolean
}) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      <div className="relative min-w-48 flex-1 sm:max-w-xs">
        <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-zinc-500" />
        <input
          className="input py-1.5 pl-8 pr-8 text-xs"
          placeholder={comic ? 'Buscar edição (ex.: 12 ou título)' : 'Buscar capítulo (ex.: 105 ou título)'}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        {query && (
          <button className="absolute right-2 top-1/2 -translate-y-1/2 text-zinc-500 hover:text-white" onClick={() => setQuery('')} aria-label="Limpar busca">
            <X className="h-3.5 w-3.5" />
          </button>
        )}
      </div>
      <button className="chip" onClick={() => setDesc(!desc)} title="Alternar ordem">
        {desc ? <ArrowDownWideNarrow className="h-3.5 w-3.5" /> : <ArrowUpNarrowWide className="h-3.5 w-3.5" />}
        {desc ? 'Mais recentes primeiro' : 'Mais antigos primeiro'}
      </button>
    </div>
  )
}
