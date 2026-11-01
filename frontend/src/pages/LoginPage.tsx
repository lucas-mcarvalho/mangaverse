import { useState, type FormEvent } from 'react'
import { LogIn } from 'lucide-react'
import { errorMessage } from '../api/client'
import { Spinner } from '../components/ui'
import { useAuth } from '../hooks/useAuth'

export default function LoginPage() {
  const { login } = useAuth()
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const submit = async (e: FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await login(username, password)
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="relative flex min-h-full items-center justify-center overflow-hidden p-4">
      <div className="pointer-events-none absolute -left-40 -top-40 h-[500px] w-[500px] rounded-full bg-accent-600/20 blur-3xl" />
      <div className="pointer-events-none absolute -bottom-40 -right-40 h-[500px] w-[500px] rounded-full bg-pink-accent/10 blur-3xl" />
      <form onSubmit={submit} className="card relative w-full max-w-sm space-y-5 p-8 shadow-2xl animate-fade-in">
        <div className="flex flex-col items-center gap-3 text-center">
          <img src="/favicon.svg" alt="" className="h-14 w-14" />
          <div>
            <h1 className="bg-gradient-to-r from-accent-400 to-pink-accent bg-clip-text text-2xl font-bold text-transparent">MangaVerse</h1>
            <p className="mt-1 text-sm text-zinc-400">Sua biblioteca de mangás e HQs</p>
          </div>
        </div>
        <label className="block space-y-1.5">
          <span className="text-sm text-zinc-300">Usuário</span>
          <input className="input" value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required />
        </label>
        <label className="block space-y-1.5">
          <span className="text-sm text-zinc-300">Senha</span>
          <input className="input" type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required autoFocus />
        </label>
        {error && <p className="rounded-lg bg-red-500/10 px-3 py-2 text-sm text-red-300">{error}</p>}
        <button className="btn-primary w-full py-2.5" disabled={busy}>{busy ? <Spinner className="h-4 w-4 text-white" /> : <LogIn className="h-4 w-4" />} Entrar</button>
      </form>
    </div>
  )
}
