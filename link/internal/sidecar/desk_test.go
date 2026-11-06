package sidecar

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// fakeDesk is the public listener as far as the sidecar meets it: auth/status and the token refresh
// judged by the bearer and the secret, and whatever routes a test adds.
type fakeDesk struct {
	mux      *http.ServeMux
	mu       sync.Mutex
	requests []*http.Request
	refresh  map[string]bool
}

func newFakeDesk() *fakeDesk {
	d := &fakeDesk{mux: http.NewServeMux(), refresh: map[string]bool{"good-2": true}}
	d.mux.HandleFunc("/api/rest/auth/status", d.authStatus)
	d.mux.HandleFunc("/api/rest/cloud/session/token", d.token)
	d.mux.HandleFunc("/echo", func(w http.ResponseWriter, r *http.Request) {
		d.record(r)
		body, _ := io.ReadAll(r.Body)
		w.Header().Set("Content-Type", "text/plain")
		w.Header().Set("Set-Cookie", "session=abc")
		_, _ = w.Write(body)
	})
	return d
}

func (d *fakeDesk) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Header.Get(linkHeader) != testSecret {
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"Not signed in","code":"unauthenticated"}`))
		return
	}
	d.mux.ServeHTTP(w, r)
}

func (d *fakeDesk) record(r *http.Request) {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.requests = append(d.requests, r)
}

func (d *fakeDesk) last() *http.Request {
	d.mu.Lock()
	defer d.mu.Unlock()
	if len(d.requests) == 0 {
		return nil
	}
	return d.requests[len(d.requests)-1]
}

func bearer(r *http.Request) string {
	return strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
}

func (d *fakeDesk) authStatus(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	// The framing fixture's GET asks with accept; ctl never does.
	if r.Header.Get("Accept") == "application/json" && r.Header.Get("Origin") != "" {
		_, _ = w.Write([]byte(`{"loggedIn":true}`))
		return
	}
	switch bearer(r) {
	case "good", "good-2":
		_, _ = w.Write([]byte(`{"setupRequired":false,"authenticated":true,"user":{"uuid":"u","username":"chris","displayName":"Chris","role":"ADMIN"}}`))
	case "unlinked":
		_, _ = w.Write([]byte(`{"setupRequired":false,"authenticated":false,"unlinked":true,"email":"a@example.com"}`))
	default:
		w.WriteHeader(http.StatusUnauthorized)
		_, _ = w.Write([]byte(`{"error":"This desk refused the session's token","code":"TOKEN_EXPIRED"}`))
	}
}

func (d *fakeDesk) token(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodPost && d.refresh[bearer(r)] {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	w.WriteHeader(http.StatusUnauthorized)
	_, _ = w.Write([]byte(`{"error":"mismatch","code":"REMOTE_SESSION_MISMATCH"}`))
}

// wsEcho upgrades /ws-echo and echoes each message, closing 4401 when sent "bye".
func (d *fakeDesk) wsEcho() {
	d.mux.HandleFunc("/ws-echo", func(w http.ResponseWriter, r *http.Request) {
		d.record(r)
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true, Subprotocols: []string{"lighting7.v1"}})
		if err != nil {
			return
		}
		conn.SetReadLimit(-1)
		ctx := context.Background()
		for {
			typ, data, err := conn.Read(ctx)
			if err != nil {
				return
			}
			if string(data) == "bye" {
				_ = conn.Close(4401, "session ended")
				return
			}
			if err := conn.Write(ctx, typ, data); err != nil {
				return
			}
		}
	})
}

func jsonOf(v any) string {
	b, _ := json.Marshal(v)
	return string(b)
}

var _ = time.Second
