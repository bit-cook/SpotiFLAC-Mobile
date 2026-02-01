package gobackend

import (
	"fmt"
	"sync"
	"time"
)

type TrackIDCacheEntry struct {
	TidalTrackID  int64
	QobuzTrackID  int64
	AmazonTrackID string
	ExpiresAt     time.Time
}

type TrackIDCache struct {
	cache           map[string]*TrackIDCacheEntry
	mu              sync.RWMutex
	ttl             time.Duration
	lastCleanup     time.Time
	cleanupInterval time.Duration
}

var (
	globalTrackIDCache *TrackIDCache
	trackIDCacheOnce   sync.Once
)

func GetTrackIDCache() *TrackIDCache {
	trackIDCacheOnce.Do(func() {
		globalTrackIDCache = &TrackIDCache{
			cache:           make(map[string]*TrackIDCacheEntry),
			ttl:             30 * time.Minute,
			cleanupInterval: 5 * time.Minute,
		}
	})
	return globalTrackIDCache
}

func (c *TrackIDCache) Get(isrc string) *TrackIDCacheEntry {
	c.mu.RLock()
	entry, exists := c.cache[isrc]
	if !exists {
		c.mu.RUnlock()
		return nil
	}
	expired := time.Now().After(entry.ExpiresAt)
	c.mu.RUnlock()

	if !expired {
		return entry
	}

	c.mu.Lock()
	entry, exists = c.cache[isrc]
	if exists && time.Now().After(entry.ExpiresAt) {
		delete(c.cache, isrc)
	}
	c.mu.Unlock()
	return nil
}

func (c *TrackIDCache) pruneExpiredLocked(now time.Time) {
	for key, entry := range c.cache {
		if now.After(entry.ExpiresAt) {
			delete(c.cache, key)
		}
	}
}

func (c *TrackIDCache) SetTidal(isrc string, trackID int64) {
	c.mu.Lock()
	defer c.mu.Unlock()

	entry, exists := c.cache[isrc]
	if !exists {
		entry = &TrackIDCacheEntry{}
		c.cache[isrc] = entry
	}
	entry.TidalTrackID = trackID
	now := time.Now()
	entry.ExpiresAt = now.Add(c.ttl)

	if c.cleanupInterval > 0 && (c.lastCleanup.IsZero() || now.Sub(c.lastCleanup) >= c.cleanupInterval) {
		c.pruneExpiredLocked(now)
		c.lastCleanup = now
	}
}

func (c *TrackIDCache) SetQobuz(isrc string, trackID int64) {
	c.mu.Lock()
	defer c.mu.Unlock()

	entry, exists := c.cache[isrc]
	if !exists {
		entry = &TrackIDCacheEntry{}
		c.cache[isrc] = entry
	}
	entry.QobuzTrackID = trackID
	now := time.Now()
	entry.ExpiresAt = now.Add(c.ttl)

	if c.cleanupInterval > 0 && (c.lastCleanup.IsZero() || now.Sub(c.lastCleanup) >= c.cleanupInterval) {
		c.pruneExpiredLocked(now)
		c.lastCleanup = now
	}
}

func (c *TrackIDCache) SetAmazon(isrc string, trackID string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	entry, exists := c.cache[isrc]
	if !exists {
		entry = &TrackIDCacheEntry{}
		c.cache[isrc] = entry
	}
	entry.AmazonTrackID = trackID
	now := time.Now()
	entry.ExpiresAt = now.Add(c.ttl)

	if c.cleanupInterval > 0 && (c.lastCleanup.IsZero() || now.Sub(c.lastCleanup) >= c.cleanupInterval) {
		c.pruneExpiredLocked(now)
		c.lastCleanup = now
	}
}

func (c *TrackIDCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.cache = make(map[string]*TrackIDCacheEntry)
}

func (c *TrackIDCache) Size() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.cache)
}

type ParallelDownloadResult struct {
	CoverData  []byte
	LyricsData *LyricsResponse
	LyricsLRC  string
	CoverErr   error
	LyricsErr  error
}

func FetchCoverAndLyricsParallel(
	coverURL string,
	maxQualityCover bool,
	spotifyID string,
	trackName string,
	artistName string,
	embedLyrics bool,
	durationMs int64,
) *ParallelDownloadResult {
	result := &ParallelDownloadResult{}
	var wg sync.WaitGroup

	if coverURL != "" {
		wg.Add(1)
		go func() {
			defer wg.Done()
			data, err := downloadCoverToMemory(coverURL, maxQualityCover)
			if err != nil {
				result.CoverErr = err
			} else {
				result.CoverData = data
			}
		}()
	}

	if embedLyrics {
		wg.Add(1)
		go func() {
			defer wg.Done()
			client := NewLyricsClient()
			durationSec := float64(durationMs) / 1000.0
			lyrics, err := client.FetchLyricsAllSources(spotifyID, trackName, artistName, durationSec)
			if err != nil {
				result.LyricsErr = err
			} else if lyrics != nil && len(lyrics.Lines) > 0 {
				result.LyricsData = lyrics
				result.LyricsLRC = convertToLRCWithMetadata(lyrics, trackName, artistName)
			} else {
				result.LyricsErr = fmt.Errorf("no lyrics found")
			}
		}()
	}

	wg.Wait()
	return result
}

type PreWarmCacheRequest struct {
	ISRC       string
	TrackName  string
	ArtistName string
	SpotifyID  string
	Service    string
}

func PreWarmTrackCache(requests []PreWarmCacheRequest) {
	if len(requests) == 0 {
		return
	}

	cache := GetTrackIDCache()

	semaphore := make(chan struct{}, 3)
	var wg sync.WaitGroup

	for _, req := range requests {
		if cached := cache.Get(req.ISRC); cached != nil {
			continue
		}

		wg.Add(1)
		go func(r PreWarmCacheRequest) {
			defer wg.Done()
			semaphore <- struct{}{}
			defer func() { <-semaphore }()

			switch r.Service {
			case "tidal":
				preWarmTidalCache(r.ISRC, r.TrackName, r.ArtistName)
			case "qobuz":
				preWarmQobuzCache(r.ISRC)
			case "amazon":
				preWarmAmazonCache(r.ISRC, r.SpotifyID)
			}
		}(req)
	}

	wg.Wait()
}

func preWarmTidalCache(isrc, _, _ string) {
	downloader := NewTidalDownloader()
	track, err := downloader.SearchTrackByISRC(isrc)
	if err == nil && track != nil {
		GetTrackIDCache().SetTidal(isrc, track.ID)
	}
}

func preWarmQobuzCache(isrc string) {
	downloader := NewQobuzDownloader()
	track, err := downloader.SearchTrackByISRC(isrc)
	if err == nil && track != nil {
		GetTrackIDCache().SetQobuz(isrc, track.ID)
	}
}

func preWarmAmazonCache(isrc, spotifyID string) {
	client := NewSongLinkClient()
	availability, err := client.CheckTrackAvailability(spotifyID, isrc)
	if err == nil && availability != nil && availability.Amazon {
		GetTrackIDCache().SetAmazon(isrc, availability.AmazonURL)
	}
}

func PreWarmCache(tracksJSON string) error {
	var requests []PreWarmCacheRequest

	go PreWarmTrackCache(requests)
	return nil
}

func ClearTrackCache() {
	GetTrackIDCache().Clear()
}

func GetCacheSize() int {
	return GetTrackIDCache().Size()
}
