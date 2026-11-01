import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react'
import { api, ensureCsrf, onUnauthorized } from '../api/client'
import type { User } from '../api/types'

interface AuthState {
  user: User | null
  loading: boolean
  login: (username: string, password: string) => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    ensureCsrf()
      .then(() => api.get<User>('/auth/me'))
      .then((r) => active && setUser(r.data))
      .catch(() => active && setUser(null))
      .finally(() => active && setLoading(false))
    const off = onUnauthorized(() => setUser(null))
    return () => {
      active = false
      off()
    }
  }, [])

  const login = useCallback(async (username: string, password: string) => {
    await ensureCsrf()
    const r = await api.post<User>('/auth/login', { username, password })
    setUser(r.data)
  }, [])

  const logout = useCallback(async () => {
    try {
      await api.post('/auth/logout')
    } finally {
      setUser(null)
      await ensureCsrf().catch(() => undefined)
    }
  }, [])

  return <AuthContext.Provider value={{ user, loading, login, logout }}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth fora do AuthProvider')
  return ctx
}
