package api

import (
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

type Service struct {
	token, key, mediaDir, endpoint string
	httpClient                     *http.Client
	mu                             sync.Mutex
	window                         time.Time
	count                          int
	home                           []byte
	homeTime                       time.Time
}

func New(token, apiKey, mediaDir string) *Service {
	return &Service{token: token, key: apiKey, mediaDir: mediaDir,
		endpoint:   "https://www.googleapis.com/youtube/v3/",
		httpClient: &http.Client{Timeout: 9 * time.Second, CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse }},
	}
}

func (s *Service) Register(mux *http.ServeMux) {
	mux.HandleFunc("GET /health", func(w http.ResponseWriter, r *http.Request) {
		jsonResponse(w, 200, map[string]any{"ok": true, "service": "YouFree relay", "time": time.Now().UTC().Format(time.RFC3339)})
	})
	mux.HandleFunc("GET /api/v1/status", s.auth(s.status))
	mux.HandleFunc("GET /api/v1/home", s.auth(s.homeFeed))
	mux.HandleFunc("GET /api/v1/search", s.auth(s.search))
	mux.HandleFunc("GET /api/v1/video/{id}", s.auth(s.video))
	mux.HandleFunc("GET /api/v1/comments/{id}", s.auth(s.comments))
	mux.HandleFunc("GET /api/v1/channel/{id}", s.auth(s.channel))
	mux.HandleFunc("GET /api/v1/channel/{id}/videos", s.auth(s.channelVideos))
	mux.HandleFunc("GET /api/v1/media/{name}", s.auth(s.media))
	mux.HandleFunc("GET /api/v1/thumbnail", s.auth(s.thumbnail))
}

func (s *Service) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		received := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if len(received) != len(s.token) || subtle.ConstantTimeCompare([]byte(received), []byte(s.token)) != 1 {
			jsonResponse(w, 401, map[string]string{"error": "unauthorized"})
			return
		}
		s.mu.Lock()
		if time.Since(s.window) > time.Minute {
			s.window = time.Now()
			s.count = 0
		}
		s.count++
		hitLimit := s.count > 150
		s.mu.Unlock()
		if hitLimit {
			jsonResponse(w, 429, map[string]string{"error": "rate limit"})
			return
		}
		next(w, r)
	}
}

func (s *Service) status(w http.ResponseWriter, _ *http.Request) {
	jsonResponse(w, 200, map[string]any{"ok": true, "youtubeApiEnabled": s.key != "", "relay": "tls-connect", "media3": true})
}

func (s *Service) yt(resource string, args url.Values) (map[string]any, error) {
	if s.key == "" {
		return nil, errors.New("YOUTUBE_API_KEY is not configured")
	}
	args.Set("key", s.key)
	target := s.endpoint + resource + "?" + args.Encode()
	resp, err := s.httpClient.Get(target)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		return nil, errors.New("YouTube API returned HTTP " + http.StatusText(resp.StatusCode))
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, 3<<20))
	if err != nil {
		return nil, err
	}
	var result map[string]any
	if err = json.Unmarshal(body, &result); err != nil {
		return nil, err
	}
	return result, nil
}

func (s *Service) homeFeed(w http.ResponseWriter, r *http.Request) {
	s.mu.Lock()
	if len(s.home) > 0 && time.Since(s.homeTime) < 5*time.Minute {
		buf := append([]byte(nil), s.home...)
		s.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write(buf)
		return
	}
	s.mu.Unlock()
	raw, err := s.yt("videos", url.Values{"part": {"snippet,statistics,contentDetails,status"}, "chart": {"mostPopular"}, "regionCode": {"RU"}, "maxResults": {"25"}})
	if err != nil {
		fail(w, err)
		return
	}
	data := mapFeed(raw, false)
	buf, _ := json.Marshal(data)
	s.mu.Lock()
	s.home = buf
	s.homeTime = time.Now()
	s.mu.Unlock()
	w.Header().Set("Content-Type", "application/json")
	_, _ = w.Write(buf)
}

func (s *Service) search(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if len(q) < 2 || len(q) > 240 {
		jsonResponse(w, 400, map[string]string{"error": "q must be 2..240 bytes"})
		return
	}
	kind := r.URL.Query().Get("type")
	if kind != "channel" {
		kind = "video"
	}
	page := r.URL.Query().Get("pageToken")
	if len(page) > 200 {
		jsonResponse(w, 400, map[string]string{"error": "invalid page token"})
		return
	}
	args := url.Values{"part": {"snippet"}, "type": {kind}, "q": {q}, "maxResults": {"25"}, "safeSearch": {"moderate"}}
	if page != "" {
		args.Set("pageToken", page)
	}
	raw, err := s.yt("search", args)
	if err != nil {
		fail(w, err)
		return
	}
	if kind == "video" {
		raw = s.keepEmbeddable(raw)
	}
	jsonResponse(w, 200, mapFeed(raw, true))
}

func (s *Service) video(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonResponse(w, 400, map[string]string{"error": "invalid id"})
		return
	}
	raw, err := s.yt("videos", url.Values{"part": {"snippet,statistics,contentDetails,status"}, "id": {id}})
	if err != nil {
		fail(w, err)
		return
	}
	jsonResponse(w, 200, mapFeed(raw, false))
}

func (s *Service) channel(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonResponse(w, 400, map[string]string{"error": "invalid id"})
		return
	}
	raw, err := s.yt("channels", url.Values{"part": {"snippet,statistics"}, "id": {id}})
	if err != nil {
		fail(w, err)
		return
	}
	jsonResponse(w, 200, raw)
}

// Search does not return embed permissions. Ask videos.list before returning video hits.
func (s *Service) keepEmbeddable(raw map[string]any) map[string]any {
	ids := []string{}
	for _, row := range arr(raw["items"]) {
		id := str(obj(obj(row)["id"])["videoId"])
		if validID(id) {
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 {
		return raw
	}
	checks, err := s.yt("videos", url.Values{"part": {"status"}, "id": {strings.Join(ids, ",")}, "maxResults": {"50"}})
	if err != nil {
		raw["items"] = []any{}
		return raw
	} // fail closed if not verified
	good := map[string]bool{}
	for _, row := range arr(checks["items"]) {
		v := obj(row)
		st := obj(v["status"])
		if embed, ok := st["embeddable"].(bool); ok && embed {
			good[str(v["id"])] = true
		}
	}
	filtered := []any{}
	for _, row := range arr(raw["items"]) {
		if good[str(obj(obj(row)["id"])["videoId"])] {
			filtered = append(filtered, row)
		}
	}
	raw["items"] = filtered
	return raw
}

func (s *Service) channelVideos(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonResponse(w, 400, map[string]string{"error": "invalid id"})
		return
	}
	raw, err := s.yt("search", url.Values{"part": {"snippet"}, "channelId": {id}, "type": {"video"}, "order": {"date"}, "maxResults": {"25"}})
	if err != nil {
		fail(w, err)
		return
	}
	jsonResponse(w, 200, mapFeed(s.keepEmbeddable(raw), true))
}

func (s *Service) comments(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	if !validID(id) {
		jsonResponse(w, 400, map[string]string{"error": "invalid id"})
		return
	}
	raw, err := s.yt("commentThreads", url.Values{"part": {"snippet"}, "videoId": {id}, "maxResults": {"30"}, "textFormat": {"plainText"}})
	if err != nil {
		fail(w, err)
		return
	}
	out := []map[string]string{}
	for _, item := range arr(raw["items"]) {
		top := obj(obj(obj(item)["snippet"])["topLevelComment"])
		sni := obj(top["snippet"])
		out = append(out, map[string]string{"author": str(sni["authorDisplayName"]), "text": str(sni["textDisplay"]), "avatar": str(sni["authorProfileImageUrl"]), "publishedAt": str(sni["publishedAt"])})
	}
	jsonResponse(w, 200, map[string]any{"items": out})
}

// ServeContent supports Range, If-Range, and 206 for controlled, self-hosted media.
func (s *Service) media(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	if name == "" || name != filepath.Base(name) || strings.Contains(name, "..") {
		jsonResponse(w, 400, map[string]string{"error": "invalid media name"})
		return
	}
	if !(strings.HasSuffix(name, ".m3u8") || strings.HasSuffix(name, ".ts") || strings.HasSuffix(name, ".mp4") || strings.HasSuffix(name, ".mpd") || strings.HasSuffix(name, ".m4s")) {
		jsonResponse(w, 403, map[string]string{"error": "unsupported media type"})
		return
	}
	if s.mediaDir == "" {
		jsonResponse(w, 404, map[string]string{"error": "not configured"})
		return
	}
	f, err := os.Open(filepath.Join(s.mediaDir, name))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	st, err := f.Stat()
	if err != nil || !st.Mode().IsRegular() {
		http.NotFound(w, r)
		return
	}
	if strings.HasSuffix(name, ".m3u8") {
		w.Header().Set("Content-Type", "application/vnd.apple.mpegurl")
	}
	if strings.HasSuffix(name, ".ts") {
		w.Header().Set("Content-Type", "video/mp2t")
	}
	if strings.HasSuffix(name, ".mp4") {
		w.Header().Set("Content-Type", "video/mp4")
	}
	w.Header().Set("Cache-Control", "public, max-age=300")
	http.ServeContent(w, r, name, st.ModTime(), f)
}

// Proxy only YouTube CDN thumbnail images. Prevent arbitrary URL fetches and redirects.
func (s *Service) thumbnail(w http.ResponseWriter, r *http.Request) {
	raw := r.URL.Query().Get("url")
	if len(raw) > 2048 {
		http.Error(w, "invalid URL", 400)
		return
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != "https" || u.User != nil || u.Fragment != "" || (u.Port() != "" && u.Port() != "443") {
		http.Error(w, "invalid URL", 400)
		return
	}
	host := strings.ToLower(u.Hostname())
	if !(host == "ytimg.com" || strings.HasSuffix(host, ".ytimg.com") || host == "ggpht.com" || strings.HasSuffix(host, ".ggpht.com")) {
		http.Error(w, "domain not permitted", 403)
		return
	}
	req, err := http.NewRequestWithContext(r.Context(), "GET", u.String(), nil)
	if err != nil {
		http.Error(w, "invalid request", 400)
		return
	}
	resp, err := s.httpClient.Do(req)
	if err != nil {
		http.Error(w, "thumbnail fetch failed", 502)
		return
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		http.Error(w, "thumbnail unavailable", 502)
		return
	}
	typ := resp.Header.Get("Content-Type")
	if !(strings.HasPrefix(typ, "image/jpeg") || strings.HasPrefix(typ, "image/png") || strings.HasPrefix(typ, "image/webp")) {
		http.Error(w, "invalid image type", 502)
		return
	}
	w.Header().Set("Content-Type", typ)
	w.Header().Set("Cache-Control", "private, max-age=120")
	_, _ = io.CopyN(w, resp.Body, 3<<20)
}

func validID(s string) bool {
	if len(s) < 2 || len(s) > 80 {
		return false
	}
	for _, r := range s {
		if (r < 'a' || r > 'z') && (r < 'A' || r > 'Z') && (r < '0' || r > '9') && r != '-' && r != '_' {
			return false
		}
	}
	return true
}
func fail(w http.ResponseWriter, err error) {
	jsonResponse(w, 503, map[string]string{"error": err.Error()})
}
func jsonResponse(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(data)
}
func obj(x any) map[string]any { v, _ := x.(map[string]any); return v }
func arr(x any) []any          { v, _ := x.([]any); return v }
func str(x any) string         { s, _ := x.(string); return s }

func mapFeed(raw map[string]any, fromSearch bool) map[string]any {
	items := []map[string]any{}
	for _, entry := range arr(raw["items"]) {
		item := obj(entry)
		snip := obj(item["snippet"])
		id := str(item["id"])
		kind := "video"
		if fromSearch {
			identification := obj(item["id"])
			id = str(identification["videoId"])
			if id == "" {
				id = str(identification["channelId"])
				kind = "channel"
			}
		}
		if id == "" {
			continue
		}
		thumbs := obj(snip["thumbnails"])
		best := obj(thumbs["high"])
		if len(best) == 0 {
			best = obj(thumbs["medium"])
		}
		if len(best) == 0 {
			best = obj(thumbs["default"])
		}
		if !fromSearch {
			if permitted, ok := obj(item["status"])["embeddable"].(bool); !ok || !permitted {
				continue
			}
		}
		items = append(items, map[string]any{
			"id": id, "kind": kind, "title": str(snip["title"]), "channelTitle": str(snip["channelTitle"]),
			"channelId": str(snip["channelId"]), "thumbnail": str(best["url"]),
			"description": str(snip["description"]), "publishedAt": str(snip["publishedAt"]),
			"duration":   str(obj(item["contentDetails"])["duration"]),
			"viewCount":  str(obj(item["statistics"])["viewCount"]),
			"embeddable": obj(item["status"])["embeddable"],
		})
	}
	return map[string]any{"items": items, "nextPageToken": str(raw["nextPageToken"])}
}
