export function loadSourcePreference(contentId: string): string {
  try {
    return localStorage.getItem(`chapter-source:${contentId}`) ?? 'all'
  } catch {
    return 'all'
  }
}

export function saveSourcePreference(contentId: string, source: string) {
  try {
    localStorage.setItem(`chapter-source:${contentId}`, source)
  } catch {
    // A leitura continua quando o navegador não permite armazenamento local.
  }
}
