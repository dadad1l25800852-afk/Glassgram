package api

import (
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestStatusAuthenticationAndSearch(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Query().Get("key") != "key123" {
			t.Error("key missing")
		}
		if strings.HasSuffix(r.URL.Path, "/videos") {
			_ = json.NewEncoder(w).Encode(map[string]any{"items": []any{map[string]any{"id": "abc123", "status": map[string]any{"embeddable": true}}}})
			return
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"items": []any{
			map[string]any{"id": map[string]any{"videoId": "abc123"}, "snippet": map[string]any{"title": "Video", "channelTitle": "Channel", "thumbnails": map[string]any{"high": map[string]any{"url": "https://i.ytimg.com/test"}}}}}})
	}))
	defer upstream.Close()
	s := New("long-test-token-1234567890", "key123", "")
	s.endpoint = upstream.URL + "/"
	mux := http.NewServeMux()
	s.Register(mux)
	server := httptest.NewServer(mux)
	defer server.Close()
	raw, err := http.Get(server.URL + "/api/v1/status")
	if err != nil {
		t.Fatal(err)
	}
	if raw.StatusCode != 401 {
		t.Fatalf("expected 401 got %d", raw.StatusCode)
	}
	raw.Body.Close()
	req, _ := http.NewRequest("GET", server.URL+"/api/v1/search?q=cat", nil)
	req.Header.Set("Authorization", "Bearer long-test-token-1234567890")
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 200 || !strings.Contains(string(data), "abc123") {
		t.Fatalf("search status %d response %s", resp.StatusCode, data)
	}
}

func TestRangeAndTraversal(t *testing.T) {
	dir := t.TempDir()
	os.WriteFile(filepath.Join(dir, "movie.mp4"), []byte("0123456789"), 0600)
	s := New("long-test-token-1234567890", "", dir)
	mux := http.NewServeMux()
	s.Register(mux)
	server := httptest.NewServer(mux)
	defer server.Close()
	req, _ := http.NewRequest("GET", server.URL+"/api/v1/media/movie.mp4", nil)
	req.Header.Set("Authorization", "Bearer long-test-token-1234567890")
	req.Header.Set("Range", "bytes=2-5")
	resp, err := server.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode != 206 || string(b) != "2345" {
		t.Fatalf("status=%d body=%s", resp.StatusCode, b)
	}
	if !validID("UC_ab-12") || validID("../../etc/passwd") {
		t.Fatal("validation failed")
	}
	if _, err := url.ParseRequestURI("/api/v1/media/../../etc/passwd"); err != nil {
		t.Fatal(err)
	}
}
