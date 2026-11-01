import axios, { AxiosError } from 'axios'

export const api = axios.create({
  baseURL: '/api',
  withCredentials: true,
  xsrfCookieName: 'XSRF-TOKEN',
  xsrfHeaderName: 'X-XSRF-TOKEN',
  timeout: 120_000,
})

type Listener = () => void
const unauthorizedListeners = new Set<Listener>()

export function onUnauthorized(listener: Listener) {
  unauthorizedListeners.add(listener)
  return () => unauthorizedListeners.delete(listener)
}

api.interceptors.response.use(
  (r) => r,
  (error: AxiosError) => {
    if (error.response?.status === 401 && !error.config?.url?.startsWith('/auth/login')) {
      unauthorizedListeners.forEach((l) => l())
    }
    return Promise.reject(error)
  },
)

/** Extrai a mensagem amigável de um ProblemDetail do backend. */
export function errorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const data = error.response?.data as { detail?: string; title?: string } | undefined
    if (data?.detail) return data.detail
    if (error.code === 'ECONNABORTED') return 'Tempo limite excedido.'
    if (!error.response) return 'Não foi possível contatar o servidor.'
    return data?.title ?? `Erro ${error.response.status}`
  }
  return error instanceof Error ? error.message : 'Erro inesperado.'
}

export async function ensureCsrf() {
  await api.get('/auth/csrf')
}
