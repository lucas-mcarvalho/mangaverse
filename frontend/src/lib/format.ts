import type { ContentType, DownloadStatus, LibraryStatus } from '../api/types'

/** Converte a referência de capa vinda da API em URL utilizável no <img>. */
export function imageUrl(raw: string | null | undefined): string | null {
  if (!raw) return null
  if (raw.startsWith('/api/')) return raw
  return `/api/images?src=${encodeURIComponent(raw)}`
}

export const typeLabel: Record<ContentType, string> = {
  MANGA: 'Mangá',
  MANHWA: 'Manhwa',
  MANHUA: 'Manhua',
  COMIC: 'HQ',
}

export const statusLabel: Record<LibraryStatus, string> = {
  READING: 'Lendo',
  PLAN_TO_READ: 'Quero ler',
  COMPLETED: 'Concluído',
  PAUSED: 'Pausado',
  DROPPED: 'Abandonado',
}

export const downloadStatusLabel: Record<DownloadStatus, string> = {
  QUEUED: 'Na fila',
  DOWNLOADING: 'Baixando',
  PAUSED: 'Pausado',
  COMPLETED: 'Concluído',
  FAILED: 'Falhou',
  CANCELLED: 'Cancelado',
}

export const publicationLabel: Record<string, string> = {
  ongoing: 'Em andamento',
  completed: 'Finalizado',
  hiatus: 'Em hiato',
  cancelled: 'Cancelado',
}

const languageNames: Record<string, string> = {
  'pt-br': 'Português (BR)', pt: 'Português', en: 'Inglês', es: 'Espanhol', 'es-la': 'Espanhol (LA)',
  ja: 'Japonês', ko: 'Coreano', zh: 'Chinês', fr: 'Francês', it: 'Italiano', de: 'Alemão', ru: 'Russo',
}

export function languageLabel(code: string | null | undefined): string {
  if (!code) return '—'
  return languageNames[code.toLowerCase()] ?? code
}

export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || bytes <= 0) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  const i = Math.min(units.length - 1, Math.floor(Math.log(bytes) / Math.log(1024)))
  return `${(bytes / 1024 ** i).toFixed(i === 0 ? 0 : 1)} ${units[i]}`
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return '—'
  return new Date(iso).toLocaleDateString('pt-BR', { day: '2-digit', month: 'short', year: 'numeric' })
}

export function timeAgo(iso: string | null | undefined): string {
  if (!iso) return 'nunca'
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60) return 'agora'
  if (diff < 3600) return `há ${Math.floor(diff / 60)} min`
  if (diff < 86400) return `há ${Math.floor(diff / 3600)} h`
  if (diff < 86400 * 30) return `há ${Math.floor(diff / 86400)} d`
  return formatDate(iso)
}

export function cx(...classes: (string | false | null | undefined)[]): string {
  return classes.filter(Boolean).join(' ')
}
