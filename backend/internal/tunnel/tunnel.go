// Package tunnel forwards encrypted TLS streams without decrypting YouTube content.
// It is an allowlisted, authenticated CONNECT relay, never a general open proxy.
package tunnel

import (
	"crypto/subtle"
	"io"
	"net"
	"net/http"
	"strings"
	"time"
)

type Proxy struct {
	token string
	slots chan struct{}
	dial  func(network, address string, timeout time.Duration) (net.Conn, error)
}

func New(token string, concurrency int) *Proxy {
	if concurrency < 1 {
		concurrency = 1
	}
	return &Proxy{token: token, slots: make(chan struct{}, concurrency), dial: net.DialTimeout}
}

var allowed = []string{
	"youtube.com", "youtube-nocookie.com", "googlevideo.com", "ytimg.com",
	"ggpht.com", "google.com", "googleapis.com", "gstatic.com",
	"doubleclick.net", "googleadservices.com", "googlesyndication.com", "googleusercontent.com", "google-analytics.com",
}

func AllowTarget(hostport string) bool {
	host, port, err := net.SplitHostPort(hostport)
	if err != nil || port != "443" {
		return false
	}
	host = strings.TrimSuffix(strings.ToLower(host), ".")
	if strings.ContainsAny(host, "@/%\\ ") || len(host) > 253 {
		return false
	}
	if net.ParseIP(host) != nil {
		return false
	}
	for _, suffix := range allowed {
		if host == suffix || strings.HasSuffix(host, "."+suffix) {
			return true
		}
	}
	return false
}

func (p *Proxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodConnect {
		http.Error(w, "CONNECT only", http.StatusMethodNotAllowed)
		return
	}
	supplied := strings.TrimPrefix(r.Header.Get("Proxy-Authorization"), "Bearer ")
	if len(supplied) != len(p.token) || subtle.ConstantTimeCompare([]byte(supplied), []byte(p.token)) != 1 {
		http.Error(w, "proxy authentication required", http.StatusProxyAuthRequired)
		return
	}
	if !AllowTarget(r.Host) {
		http.Error(w, "host not allowed", http.StatusForbidden)
		return
	}
	select {
	case p.slots <- struct{}{}:
		defer func() { <-p.slots }()
	default:
		http.Error(w, "too many connections", http.StatusServiceUnavailable)
		return
	}
	upstream, err := p.dial("tcp", r.Host, 8*time.Second)
	if err != nil {
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	defer upstream.Close()
	hj, ok := w.(http.Hijacker)
	if !ok {
		http.Error(w, "HTTP/1.1 required", http.StatusHTTPVersionNotSupported)
		return
	}
	client, rw, err := hj.Hijack()
	if err != nil {
		return
	}
	defer client.Close()
	if _, err = client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n")); err != nil {
		return
	}
	_ = upstream.SetDeadline(time.Now().Add(20 * time.Minute))
	_ = client.SetDeadline(time.Now().Add(20 * time.Minute))
	done := make(chan struct{}, 2)
	go func() {
		_, _ = io.Copy(upstream, rw)
		closeWrite(upstream)
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(client, upstream)
		closeWrite(client)
		done <- struct{}{}
	}()
	<-done
	<-done
}

func closeWrite(c net.Conn) {
	if tc, ok := c.(*net.TCPConn); ok {
		_ = tc.CloseWrite()
	}
}
