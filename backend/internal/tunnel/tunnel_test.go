package tunnel

import (
	"bufio"
	"crypto/tls"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestHostAllowlist(t *testing.T) {
	yes := []string{"youtube.com:443", "r1---sn.googlevideo.com:443", "i.ytimg.com:443", "WWW.YOUTUBE.COM:443"}
	no := []string{"localhost:443", "1.1.1.1:443", "example.com:443", "youtube.com.evil.org:443", "evilgooglevideo.com:443", "youtube.com:80", "youtube.com:8443", "youtube.com:443@host:443", "[::1]:443"}
	for _, x := range yes {
		if !AllowTarget(x) {
			t.Errorf("should allow %s", x)
		}
	}
	for _, x := range no {
		if AllowTarget(x) {
			t.Errorf("should reject %s", x)
		}
	}
}
func TestAuthenticationAndTargetValidation(t *testing.T) {
	s := httptest.NewServer(New("a-very-long-testing-token-1234", 4))
	defer s.Close()
	req, _ := http.NewRequest(http.MethodConnect, s.URL, nil)
	req.Host = "evil.example:443"
	res, err := s.Client().Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if res.StatusCode != 407 {
		t.Fatalf("expected 407 got %d", res.StatusCode)
	}
}

// Exercise an HTTPS CONNECT tunnel end to end without reaching the public network.
// net.Pipe stands in for the actual youtube.com:443 TCP socket.
func TestTLSTunnelRoundTrip(t *testing.T) {
	token := "a-very-long-testing-token-1234"
	p := New(token, 4)
	p.dial = func(network, address string, timeout time.Duration) (net.Conn, error) {
		if address != "www.youtube.com:443" {
			t.Errorf("unexpected destination: %s", address)
		}
		client, server := net.Pipe()
		go func() {
			defer server.Close()
			buffer := make([]byte, 64)
			for {
				n, err := server.Read(buffer)
				if err != nil {
					return
				}
				if _, err = server.Write(buffer[:n]); err != nil {
					return
				}
			}
		}()
		return client, nil
	}
	server := httptest.NewTLSServer(p)
	defer server.Close()
	address := strings.TrimPrefix(server.URL, "https://")
	conn, err := tls.DialWithDialer(&net.Dialer{Timeout: 3 * time.Second}, "tcp", address,
		&tls.Config{InsecureSkipVerify: true, MinVersion: tls.VersionTLS12}) // test-only mock cert
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	conn.SetDeadline(time.Now().Add(3 * time.Second))
	_, err = fmt.Fprintf(conn, "CONNECT www.youtube.com:443 HTTP/1.1\r\nHost: www.youtube.com:443\r\nProxy-Authorization: Bearer %s\r\n\r\n", token)
	if err != nil {
		t.Fatal(err)
	}
	reader := bufio.NewReader(conn)
	status, err := reader.ReadString('\n')
	if err != nil || !strings.Contains(status, "200 Connection Established") {
		t.Fatalf("status=%q err=%v", status, err)
	}
	_, _ = reader.ReadString('\n') // blank line
	_, err = conn.Write([]byte("echo-payload"))
	if err != nil {
		t.Fatal(err)
	}
	payload := make([]byte, len("echo-payload"))
	if _, err = io.ReadFull(reader, payload); err != nil {
		t.Fatal(err)
	}
	if string(payload) != "echo-payload" {
		t.Fatalf("unexpected bytes: %s", payload)
	}
}
