import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router'
import { Layout } from './components/Layout'
import { PageLoader } from './components/ui'
import { useAuth } from './hooks/useAuth'
import LoginPage from './pages/LoginPage'

const DashboardPage = lazy(() => import('./pages/DashboardPage'))
const ExplorePage = lazy(() => import('./pages/ExplorePage'))
const SearchPage = lazy(() => import('./pages/SearchPage'))
const LibraryPage = lazy(() => import('./pages/LibraryPage'))
const RemoteTitlePage = lazy(() => import('./pages/RemoteTitlePage'))
const ContentPage = lazy(() => import('./pages/ContentPage'))
const ReaderPage = lazy(() => import('./pages/ReaderPage'))
const DownloadsPage = lazy(() => import('./pages/DownloadsPage'))
const SourcesPage = lazy(() => import('./pages/SourcesPage'))
const SettingsPage = lazy(() => import('./pages/SettingsPage'))

export default function App() {
  const { user, loading } = useAuth()
  if (loading) {
    return <div className="flex h-full items-center justify-center"><PageLoader label="Iniciando MangaVerse…" /></div>
  }
  if (!user) {
    return (
      <Routes>
        <Route path="*" element={<LoginPage />} />
      </Routes>
    )
  }
  return (
    <Suspense fallback={<PageLoader />}>
      <Routes>
        <Route path="/ler/:chapterId" element={<ReaderPage />} />
        <Route element={<Layout />}>
          <Route index element={<DashboardPage />} />
          <Route path="explorar" element={<ExplorePage />} />
          <Route path="pesquisa" element={<SearchPage />} />
          <Route path="biblioteca" element={<LibraryPage />} />
          <Route path="titulo/:providerCode/:externalId" element={<RemoteTitlePage />} />
          <Route path="conteudo/:contentId" element={<ContentPage />} />
          <Route path="downloads" element={<DownloadsPage />} />
          <Route path="fontes" element={<SourcesPage />} />
          <Route path="configuracoes" element={<SettingsPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Route>
      </Routes>
    </Suspense>
  )
}
