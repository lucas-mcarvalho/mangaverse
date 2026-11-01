/** Agrupa capítulos/edições por volume, mantendo a ordem original. */
export function groupByVolume<T extends { volume: string | null }>(items: T[]): { volume: string | null; items: T[] }[] {
  const groups: { volume: string | null; items: T[] }[] = []
  const index = new Map<string, number>()
  for (const item of items) {
    const key = item.volume ?? '__none__'
    if (!index.has(key)) {
      index.set(key, groups.length)
      groups.push({ volume: item.volume, items: [] })
    }
    groups[index.get(key)!].items.push(item)
  }
  return groups.sort((a, b) => {
    if (a.volume == null) return 1
    if (b.volume == null) return -1
    return Number(a.volume) - Number(b.volume) || a.volume.localeCompare(b.volume)
  })
}
