export type ContentType = 'MANGA' | 'MANHWA' | 'MANHUA' | 'COMIC'
export type LibraryStatus = 'READING' | 'PLAN_TO_READ' | 'COMPLETED' | 'PAUSED' | 'DROPPED'
export type DownloadStatus = 'QUEUED' | 'DOWNLOADING' | 'PAUSED' | 'COMPLETED' | 'FAILED' | 'CANCELLED'

export interface User { id: string; username: string; role: string }

export interface Capabilities {
  search: boolean; chapters: boolean; read: boolean; download: boolean; metadataOnly: boolean; discover: boolean
}

export interface RemoteTitle {
  providerCode: string; providerName: string; externalId: string; title: string; altTitles: string[]
  description: string | null; coverUrl: string | null; type: ContentType | null; languages: string[]
  year: number | null; status: string | null; url: string | null; publisher: string | null
}

export interface SourceRef {
  providerCode: string; providerName: string; externalId: string; title: string; url: string | null
  languages: string[]; coverUrl: string | null; inLibrary: boolean
}

export interface AggregatedResult {
  key: string; title: string; altTitles: string[]; description: string | null; coverUrl: string | null
  type: ContentType | null; languages: string[]; hasPreferredLanguage: boolean; year: number | null
  status: string | null; publisher: string | null; sources: SourceRef[]; score: number; libraryContentId: string | null
}

export interface ProviderOutcome { code: string; name: string; status: 'OK' | 'ERROR' | 'TIMEOUT' | 'SKIPPED'; count: number; message: string | null; tookMs: number }
export interface SearchResponse { query: string; results: AggregatedResult[]; providers: ProviderOutcome[] }

export interface RemoteTitleDetails {
  providerCode: string; externalId: string; title: string; altTitles: string[]; description: string | null
  coverUrl: string | null; type: ContentType | null; authors: string[]; artists: string[]; publisher: string | null
  genres: string[]; characters: string[]; languages: string[]; originalLanguage: string | null; status: string | null
  year: number | null; url: string | null
}

export interface RemoteChapter {
  externalId: string; number: number | null; numberLabel: string | null; volume: string | null; title: string | null
  language: string | null; scanlator: string | null; publishedAt: string | null; pageCount: number | null
  readable: boolean; downloadable: boolean; externalUrl: string | null
}

export interface RemoteTitleView {
  providerCode: string; providerName: string; details: RemoteTitleDetails; chapters: RemoteChapter[]
  chaptersError: string | null; capabilities: Capabilities; libraryContentId: string | null; languages: string[]
}

export interface SourceView {
  id: string; providerCode: string; providerName: string; providerType: string; externalId: string
  url: string | null; language: string | null; lastSyncedAt: string | null; capabilities: Capabilities | null
}

export interface ChapterView {
  id: string; externalId: string | null; number: number | null; numberLabel: string | null; label: string
  volume: string | null; title: string | null; language: string | null; scanlator: string | null
  publishedAt: string | null; pageCount: number | null; readable: boolean; downloadable: boolean
  externalUrl: string | null; downloaded: boolean; fileSize: number | null; providerCode: string
  sourceId: string | null; read: boolean; progressPage: number | null; downloadStatus: DownloadStatus | null
  createdAt: string
}

export interface LibraryInfo {
  entryId: string; status: LibraryStatus; favorite: boolean; monitored: boolean; autoDownload: boolean
  categoryId: string | null; addedAt: string; lastCheckedAt: string | null
}

export interface ContentView {
  id: string; title: string; type: ContentType; description: string | null; authors: string[]; artists: string[]
  publisher: string | null; genres: string[]; characters: string[]; language: string | null; status: string | null
  coverUrl: string | null; year: number | null; local: boolean; localPath: string | null; sources: SourceView[]
  chapters: ChapterView[]; library: LibraryInfo | null; totalChapters: number; readChapters: number
  downloadedChapters: number; officialOnlyCount: number
}

export interface LibraryItem {
  entryId: string; contentId: string; title: string; type: ContentType; coverUrl: string | null
  status: LibraryStatus; favorite: boolean; monitored: boolean; autoDownload: boolean; categoryId: string | null
  categoryName: string | null; addedAt: string; lastReadAt: string | null; totalChapters: number
  readChapters: number; unreadChapters: number; downloadedChapters: number; activeDownloads: number
  lastReadChapterLabel: string | null; lastReadChapterId: string | null; progressPercent: number; local: boolean
}

export interface Category { id: string; name: string }

export interface ReaderChapter {
  chapterId: string; contentId: string; contentTitle: string; contentType: ContentType; label: string
  title: string | null; volume: string | null; pageCount: number; pages: string[]; prevChapterId: string | null
  nextChapterId: string | null; startPage: number; completed: boolean; local: boolean; providerCode: string | null
  defaultMode: 'rtl' | 'ltr' | 'vertical'
}

export interface DownloadView {
  id: string; chapterId: string; contentId: string; contentTitle: string; coverUrl: string | null
  chapterLabel: string; status: DownloadStatus; progress: number; totalPages: number; downloadedPages: number
  bytesDownloaded: number; speedBps: number; errorMessage: string | null; attempts: number; filePath: string | null
  autoTriggered: boolean; createdAt: string; startedAt: string | null; finishedAt: string | null
}

export interface DownloadSummary { queued: number; downloading: number; paused: number; failed: number; running: number; maxConcurrent: number }
export interface EnqueueResult { queued: number; skipped: { chapterId: string; reason: string }[] }

export interface ProviderView {
  id: string; code: string; name: string; type: 'MANGADEX' | 'COMICVINE' | 'SUWAYOMI_SOURCE' | 'LOCAL'
  enabled: boolean; priority: number; language: string | null; iconUrl: string | null; supportsSearch: boolean
  supportsDownload: boolean; metadataOnly: boolean; lastStatus: string; lastError: string | null
  lastCheckedAt: string | null; defaultContentType: 'AUTO' | ContentType | null
}

export interface SuwayomiStatus { enabled: boolean; online: boolean; baseUrl: string; version: string | null; message: string; defaultStore: string }
export interface ExtensionView {
  pkgName: string; name: string; lang: string; versionName: string; iconUrl: string | null; installed: boolean
  hasUpdate: boolean; obsolete: boolean; contentWarning: string | null; storeIndexUrl: string | null
}
export interface StoreView { name: string; indexUrl: string; badgeLabel: string; legacy: boolean }

export interface Settings {
  mangaLibraryPath: string; comicLibraryPath: string; maxConcurrentDownloads: number; autoDownloadEnabled: boolean
  monitorEnabled: boolean; monitorIntervalMinutes: number; monitorLastRunAt: string | null; languages: string[]
  contentRatings: string[]; allowedRoots: string[]; cbrSupported: boolean; cbrTool: string | null
  comicVineConfigured: boolean; suwayomiUrl: string
}

export interface DirectoryListing { path: string | null; parent: string | null; directories: string[]; roots: string[]; freeBytes: number | null }

export interface ImportResult { seriesFound: number; seriesCreated: number; chaptersAdded: number; chaptersSkipped: number; errors: string[]; contentIds: string[] }

export interface NotificationView {
  id: string; type: 'NEW_CHAPTER' | 'CHECK_FAILED' | 'DOWNLOAD_COMPLETED' | 'DOWNLOAD_FAILED'; message: string
  seen: boolean; createdAt: string; contentId: string; contentTitle: string; coverUrl: string | null; chapterId: string | null
}

export interface Dashboard {
  continueReading: { contentId: string; title: string; coverUrl: string | null; chapterId: string; chapterLabel: string; page: number; totalPages: number; completed: boolean; type: ContentType }[]
  recentlyAdded: LibraryItem[]
  latestChapters: { id: string; contentId: string; contentTitle: string; coverUrl: string | null; chapterId: string | null; message: string; createdAt: string }[]
  activeDownloads: DownloadView[]
  favoriteMangas: LibraryItem[]
  favoriteComics: LibraryItem[]
  stats: { titles: number; mangas: number; comics: number; reading: number; completed: number; favorites: number; monitored: number; chaptersDownloaded: number; unreadChapters: number }
}

export interface MonitorStatus { enabled: boolean; running: boolean; intervalMinutes: number; lastRunAt: string | null }
export interface CheckResult { contentsChecked: number; newChapters: number; autoQueued: number; errors: string[] }
