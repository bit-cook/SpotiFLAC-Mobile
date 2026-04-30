package gobackend

import (
	"context"
	"crypto/tls"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"
)

func TestSetMetadataProviderPriorityStripsRetiredBuiltIns(t *testing.T) {
	original := GetMetadataProviderPriority()
	defer SetMetadataProviderPriority(original)

	SetMetadataProviderPriority([]string{"qobuz"})
	got := GetMetadataProviderPriority()
	if len(got) != 0 {
		t.Fatalf("expected retired built-in qobuz to be stripped, got %v", got)
	}
}

func TestSetExtensionFallbackProviderIDsDedupesExtensions(t *testing.T) {
	original := GetExtensionFallbackProviderIDs()
	defer SetExtensionFallbackProviderIDs(original)

	SetExtensionFallbackProviderIDs([]string{"ext-a", "ext-a", " ext-b "})

	got := GetExtensionFallbackProviderIDs()
	want := []string{"ext-a", "ext-b"}
	if len(got) != len(want) {
		t.Fatalf("unexpected fallback provider length: got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("unexpected fallback provider at %d: got %v want %v", i, got, want)
		}
	}
}

func TestIsExtensionFallbackAllowedDefaultsToAllExtensions(t *testing.T) {
	original := GetExtensionFallbackProviderIDs()
	defer SetExtensionFallbackProviderIDs(original)

	SetExtensionFallbackProviderIDs(nil)

	if !isExtensionFallbackAllowed("custom-ext") {
		t.Fatal("expected custom extension to be allowed when no fallback allowlist is configured")
	}
}

func TestIsExtensionFallbackAllowedRespectsAllowlist(t *testing.T) {
	original := GetExtensionFallbackProviderIDs()
	defer SetExtensionFallbackProviderIDs(original)

	SetExtensionFallbackProviderIDs([]string{"allowed-ext"})

	if !isExtensionFallbackAllowed("allowed-ext") {
		t.Fatal("expected explicitly allowed extension to be permitted")
	}
	if isExtensionFallbackAllowed("blocked-ext") {
		t.Fatal("expected extension outside allowlist to be blocked")
	}
	if isExtensionFallbackAllowed("deezer") {
		t.Fatal("expected retired Deezer downloader to respect extension fallback allowlist")
	}
}

func TestSetProviderPriorityRemovesRetiredDeezerDownloader(t *testing.T) {
	original := GetProviderPriority()
	defer SetProviderPriority(original)

	SetProviderPriority([]string{"deezer", "qobuz", "custom-ext"})

	got := GetProviderPriority()
	want := []string{"custom-ext"}
	if len(got) != len(want) {
		t.Fatalf("unexpected priority length: got %v want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("unexpected priority at %d: got %v want %v", i, got, want)
		}
	}
}

func TestNormalizeDownloadDecryptionInfoPromotesLegacyKey(t *testing.T) {
	normalized := normalizeDownloadDecryptionInfo(nil, " 001122 ")
	if normalized == nil {
		t.Fatal("expected legacy decryption key to produce normalized descriptor")
	}
	if normalized.Strategy != genericFFmpegMOVDecryptionStrategy {
		t.Fatalf("strategy = %q", normalized.Strategy)
	}
	if normalized.Key != "001122" {
		t.Fatalf("key = %q", normalized.Key)
	}
	if normalized.InputFormat != "mov" {
		t.Fatalf("input format = %q", normalized.InputFormat)
	}
}

func TestNormalizeDownloadDecryptionInfoCanonicalizesMovAliases(t *testing.T) {
	normalized := normalizeDownloadDecryptionInfo(&DownloadDecryptionInfo{
		Strategy:    "mp4_decryption_key",
		Key:         "abcd",
		InputFormat: "",
	}, "")
	if normalized == nil {
		t.Fatal("expected descriptor to remain available")
	}
	if normalized.Strategy != genericFFmpegMOVDecryptionStrategy {
		t.Fatalf("strategy = %q", normalized.Strategy)
	}
	if normalized.InputFormat != "mov" {
		t.Fatalf("input format = %q", normalized.InputFormat)
	}
}

func TestExtensionDownloadUsesIsolatedRuntimeForConcurrentCalls(t *testing.T) {
	server := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(500 * time.Millisecond)
		_, _ = w.Write([]byte("ok"))
	}))
	defer server.Close()
	setPrivateIPCache("download.test", false, time.Minute)

	originalTransport := sharedTransport
	testTransport := &http.Transport{
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			return (&net.Dialer{}).DialContext(ctx, network, server.Listener.Addr().String())
		},
		TLSClientConfig: &tls.Config{InsecureSkipVerify: true},
	}
	sharedTransport = testTransport
	defer func() {
		testTransport.CloseIdleConnections()
		sharedTransport = originalTransport
	}()

	extDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(extDir, "index.js"), []byte(`
		registerExtension({
			download: function(trackID, quality, outputPath, onProgress) {
				var result = file.download('https://download.test/' + trackID, outputPath, {
					onProgress: function(written, total) {
						if (onProgress) onProgress(50);
					}
				});
				if (!result || !result.success) {
					return {
						success: false,
						error_message: result && result.error ? result.error : 'download failed',
						error_type: 'download_error'
					};
				}
				if (onProgress) onProgress(100);
				return { success: true, file_path: result.path };
			}
		});
	`), 0600); err != nil {
		t.Fatalf("write extension index: %v", err)
	}

	outputDir := t.TempDir()
	SetAllowedDownloadDirs([]string{outputDir})
	defer SetAllowedDownloadDirs(nil)

	ext := &loadedExtension{
		ID: "concurrent-download",
		Manifest: &ExtensionManifest{
			Name:        "concurrent-download",
			Description: "Concurrent download test",
			Version:     "1.0.0",
			Types:       []ExtensionType{ExtensionTypeDownloadProvider},
			Permissions: ExtensionPermissions{
				Network: []string{"download.test"},
				File:    true,
			},
		},
		Enabled:   true,
		SourceDir: extDir,
		DataDir:   t.TempDir(),
	}
	provider := newExtensionProviderWrapper(ext)

	start := time.Now()
	var wg sync.WaitGroup
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		i := i
		wg.Add(1)
		go func() {
			defer wg.Done()
			result, err := provider.Download(
				fmt.Sprintf("track-%d", i),
				"LOSSLESS",
				filepath.Join(outputDir, fmt.Sprintf("track-%d.flac", i)),
				"",
				nil,
			)
			if err != nil {
				errs <- err
				return
			}
			if result == nil || !result.Success {
				errs <- fmt.Errorf("download failed: %#v", result)
			}
		}()
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}

	if elapsed := time.Since(start); elapsed >= 850*time.Millisecond {
		t.Fatalf("expected same-extension downloads to overlap, elapsed %s", elapsed)
	}
}

func TestBuildOutputPathAddsExplicitOutputDirToAllowedDirs(t *testing.T) {
	SetAllowedDownloadDirs(nil)

	outputDir := t.TempDir()
	outputPath := buildOutputPath(DownloadRequest{
		TrackName:      "Song",
		ArtistName:     "Artist",
		OutputDir:      outputDir,
		OutputExt:      ".flac",
		FilenameFormat: "",
	})

	if !isPathInAllowedDirs(outputPath) {
		t.Fatalf("expected output path %q to be allowed", outputPath)
	}
}

func TestBuildOutputPathForExtensionAddsExplicitOutputPathDirToAllowedDirs(t *testing.T) {
	SetAllowedDownloadDirs(nil)

	outputDir := t.TempDir()
	outputPath := filepath.Join(outputDir, "custom.flac")
	ext := &loadedExtension{DataDir: t.TempDir()}

	resolved := buildOutputPathForExtension(DownloadRequest{
		OutputPath: outputPath,
	}, ext)

	if resolved != outputPath {
		t.Fatalf("resolved output path = %q", resolved)
	}
	if !isPathInAllowedDirs(outputPath) {
		t.Fatalf("expected output path %q to be allowed", outputPath)
	}
}

func TestBuildOutputPathForExtensionUsesTempDirForFDOutput(t *testing.T) {
	SetAllowedDownloadDirs(nil)

	ext := &loadedExtension{DataDir: t.TempDir()}
	resolved := buildOutputPathForExtension(DownloadRequest{
		TrackName:  "Song",
		ArtistName: "Artist",
		OutputDir:  filepath.Join("Artist", "Album"),
		OutputFD:   123,
		OutputExt:  ".flac",
	}, ext)

	expectedBase := filepath.Join(ext.DataDir, "downloads")
	if !isPathWithinBase(expectedBase, resolved) {
		t.Fatalf("expected SAF extension output under %q, got %q", expectedBase, resolved)
	}
	if !isPathInAllowedDirs(resolved) {
		t.Fatalf("expected resolved output path %q to be allowed", resolved)
	}
}

func TestShouldStopProviderFallback(t *testing.T) {
	if shouldStopProviderFallback(nil) {
		t.Fatal("nil availability should not stop fallback")
	}
	if shouldStopProviderFallback(&ExtAvailabilityResult{Available: false}) {
		t.Fatal("availability without skip_fallback should not stop fallback")
	}
	if !shouldStopProviderFallback(&ExtAvailabilityResult{Available: false, SkipFallback: true}) {
		t.Fatal("skip_fallback availability should stop fallback")
	}
}

func TestBuildExtensionFallbackStoppedResponsePrefersAvailabilityReason(t *testing.T) {
	resp := buildExtensionFallbackStoppedResponse("soundcloud", &ExtAvailabilityResult{
		Reason:       "direct SoundCloud track ID",
		SkipFallback: true,
	}, errors.New("ignored"))

	if resp.Service != "soundcloud" {
		t.Fatalf("service = %q", resp.Service)
	}
	if resp.Error != "Fallback stopped by soundcloud: direct SoundCloud track ID" {
		t.Fatalf("unexpected error message: %q", resp.Error)
	}
	if resp.ErrorType != "extension_error" {
		t.Fatalf("error type = %q", resp.ErrorType)
	}
}

func TestBuildExtensionFallbackStoppedResponseFallsBackToError(t *testing.T) {
	resp := buildExtensionFallbackStoppedResponse("soundcloud", &ExtAvailabilityResult{
		SkipFallback: true,
	}, errors.New("lookup failed"))

	if resp.Error != "Fallback stopped by soundcloud: lookup failed" {
		t.Fatalf("unexpected error message: %q", resp.Error)
	}
}

func TestShouldAbortCancelledFallbackWithCancelledError(t *testing.T) {
	if !shouldAbortCancelledFallback("", ErrDownloadCancelled) {
		t.Fatal("expected cancelled error to abort fallback")
	}
}

func TestShouldAbortCancelledFallbackWithCancelledItemState(t *testing.T) {
	const itemID = "cancelled-item"
	initDownloadCancel(itemID)
	defer clearDownloadCancel(itemID)

	cancelDownload(itemID)

	if !shouldAbortCancelledFallback(itemID, errors.New("generic failure")) {
		t.Fatal("expected cancelled item state to abort fallback even for generic errors")
	}
}

func TestCanEmbedGenreLabelRequiresExistingAbsoluteLocalFile(t *testing.T) {
	tempFile := filepath.Join(t.TempDir(), "track.flac")
	if err := os.WriteFile(tempFile, []byte("fLaC"), 0644); err != nil {
		t.Fatalf("failed to create temp file: %v", err)
	}
	tempM4A := filepath.Join(t.TempDir(), "track.m4a")
	if err := os.WriteFile(tempM4A, []byte("not-flac"), 0644); err != nil {
		t.Fatalf("failed to create temp m4a file: %v", err)
	}

	if canEmbedGenreLabel("relative.flac") {
		t.Fatal("expected relative path to be rejected")
	}
	if canEmbedGenreLabel("content://example") {
		t.Fatal("expected content URI to be rejected")
	}
	if canEmbedGenreLabel(filepath.Join(t.TempDir(), "missing.flac")) {
		t.Fatal("expected missing file to be rejected")
	}
	if canEmbedGenreLabel(tempM4A) {
		t.Fatalf("expected non-FLAC file %q to be rejected", tempM4A)
	}
	if !canEmbedGenreLabel(tempFile) {
		t.Fatalf("expected existing absolute file %q to be accepted", tempFile)
	}
}

func TestSearchTracksWithMetadataProvidersIgnoresRetiredBuiltIns(t *testing.T) {
	originalPriority := GetMetadataProviderPriority()
	originalSearch := searchBuiltInMetadataTracksFunc
	defer func() {
		SetMetadataProviderPriority(originalPriority)
		searchBuiltInMetadataTracksFunc = originalSearch
	}()

	SetMetadataProviderPriority([]string{"qobuz"})

	var calls []string
	searchBuiltInMetadataTracksFunc = func(providerID, query string, limit int) ([]ExtTrackMetadata, error) {
		calls = append(calls, providerID)
		return nil, nil
	}

	manager := getExtensionManager()
	tracks, err := manager.SearchTracksWithMetadataProviders("query", 3, false)
	if err != nil {
		t.Fatalf("SearchTracksWithMetadataProviders returned error: %v", err)
	}
	if len(tracks) != 0 {
		t.Fatalf("expected no tracks from retired built-in provider, got %+v", tracks)
	}
	if len(calls) != 0 {
		t.Fatalf("expected retired built-in provider not to be queried, got %v", calls)
	}
}
