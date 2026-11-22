package main

import (
	"crypto/tls"
	"log"
	"net/http"
	"os"
	"time"

	"github.com/youfree-project/youfree/backend/internal/api"
	"github.com/youfree-project/youfree/backend/internal/tunnel"
)

func main() {
	token := os.Getenv("YOUFREE_TOKEN")
	cert, key := os.Getenv("TLS_CERT_FILE"), os.Getenv("TLS_KEY_FILE")
	if len(token) < 24 || cert == "" || key == "" {
		log.Fatal("YOUFREE_TOKEN (>=24 characters), TLS_CERT_FILE and TLS_KEY_FILE are required")
	}
	mux := http.NewServeMux()
	a := api.New(token, os.Getenv("YOUTUBE_API_KEY"), os.Getenv("SAMPLE_MEDIA_DIR"))
	a.Register(mux)
	proxy := tunnel.New(token, 80)
	// Go 1.23 ServeMux routes CONNECT host:port; handle it before normal paths.
	h := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodConnect {
			proxy.ServeHTTP(w, r)
			return
		}
		mux.ServeHTTP(w, r)
	})
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = ":8443"
	}
	srv := &http.Server{Addr: addr, Handler: h, ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout: 45 * time.Second, MaxHeaderBytes: 16384,
		TLSConfig:    &tls.Config{MinVersion: tls.VersionTLS12},
		TLSNextProto: make(map[string]func(*http.Server, *tls.Conn, http.Handler)), // CONNECT needs HTTP/1.1 hijack
	}
	log.Printf("YouFree relay listening on %s (HTTPS, HTTP/1.1 CONNECT)", addr)
	log.Fatal(srv.ListenAndServeTLS(cert, key))
}
