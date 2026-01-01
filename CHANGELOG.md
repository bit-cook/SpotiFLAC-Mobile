# Changelog

## [1.1.0] - 2026-01-01

### Added
- **Parallel Downloads**: Download up to 3 tracks simultaneously (configurable in Settings)
  - Default: Sequential (1 at a time) for stability
  - Options: 1, 2, or 3 concurrent downloads
  - Warning about potential rate limiting from streaming services
- **Download Progress Tracking**: Real-time progress for BTS manifest downloads from Tidal
- **History Persistence**: Download history now persists across app restarts using SharedPreferences
- **Connection Pooling**: Shared HTTP transport to prevent TCP connection exhaustion during large batch downloads
- **Connection Cleanup**: Automatic cleanup of idle connections every 50 downloads and at queue end
- **GitHub & Credits Section**: Added links to SpotiFLAC Mobile and original SpotiFLAC desktop in Settings

### Fixed
- **Download Progress Bug**: Fixed 0% → 100% jump by adding proper progress tracking for BTS format downloads
- **TCP Connection Exhaustion**: Fixed slow downloads after ~300 tracks by implementing connection pooling and periodic cleanup
- **Trailing Space in Names**: Fixed download failures when playlist/album/track names have trailing spaces
- **History Loss on Debug**: History no longer disappears when sideloading via `flutter run --debug`

### Changed
- Updated version to 1.1.0

### Technical Details
- Added `concurrentDownloads` field to `AppSettings` model (default: 1, max: 3)
- Implemented worker pool pattern in `DownloadQueueNotifier` for parallel processing
- Added `SetCurrentFile()`, `SetBytesTotal()`, and `ProgressWriter` for BTS downloads in Go backend
- Added `strings.TrimSpace()` to all string fields in `DownloadTrack()` and `DownloadWithFallback()`
- Added shared `http.Transport` with connection pooling in `httputil.go`
- Added `CleanupConnections()` export for Flutter to call via method channel

## [1.0.5] - Previous Release
- Material Expressive 3 UI
- Dynamic color support
- Swipe navigation with PageView
- Settings as bottom navigation tab
- APK size optimization
