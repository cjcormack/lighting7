package sidecar

import (
	"bytes"
	"context"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// A body the desk cuts short of its Content-Length is reset, never ended as if whole.
func TestATruncatedResponseIsResetNotEnded(t *testing.T) {
	desk := newFakeDesk()
	desk.mux.HandleFunc("/short", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "100000")
		_, _ = w.Write(bytes.Repeat([]byte("a"), 50000))
		w.(http.Flusher).Flush()
		conn, _, err := w.(http.Hijacker).Hijack()
		if err == nil {
			_ = conn.Close()
		}
	})
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	c := b.open(LabelHTTP)
	c.sendJSON(t, KindHead, RequestHead{Method: "GET", Path: "/short", Headers: []Header{}})
	c.send(t, []byte{KindEnd})
	if f := c.next(t); f.Kind != KindHead {
		t.Fatalf("got %#x", f.Kind)
	}
	got := 0
	for {
		f := c.next(t)
		switch f.Kind {
		case KindData:
			got += len(f.Payload)
			continue
		case KindReset:
			if !strings.Contains(string(f.Payload), "DESK_UNREACHABLE") {
				t.Fatalf("reset %s", f.Payload)
			}
			return
		default:
			t.Fatalf("after %d bytes, %#x %q", got, f.Kind, f.Payload)
		}
	}
}

// A body the desk refuses without reading it is answered, the rest of the upload drained.
func TestAnUploadTheDeskRefusesUnreadIsAnswered(t *testing.T) {
	desk := newFakeDesk()
	desk.mux.HandleFunc("/refuse", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusRequestEntityTooLarge)
	})
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	c := b.open(LabelHTTP)
	c.sendJSON(t, KindHead, RequestHead{Method: "POST", Path: "/refuse", Headers: []Header{}})
	// The channel closes once the desk has answered, so the rest of the upload may not go.
	go func() {
		chunk := EncodeFrame(KindData, bytes.Repeat([]byte("p"), MaxChunkBytes))
		for i := 0; i < 256; i++ {
			if c.dc.Send(chunk) != nil {
				return
			}
		}
		_ = c.dc.Send([]byte{KindEnd})
	}()
	if head, _ := readResponse(t, c); head.Status != http.StatusRequestEntityTooLarge {
		t.Fatalf("status %d", head.Status)
	}
	// The session's other channels still flow.
	if head, body := request(t, b, RequestHead{Method: "POST", Path: "/echo", Headers: []Header{}}, []byte("after")); head.Status != 200 || string(body) != "after" {
		t.Fatalf("then %d %q", head.Status, body)
	}
}

// The browser's close code reaches the desk through the close handshake.
func TestTheBrowsersCloseReachesTheDesk(t *testing.T) {
	desk := newFakeDesk()
	got := make(chan websocket.StatusCode, 1)
	desk.mux.HandleFunc("/ws-hold", func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		_, _, err = conn.Read(context.Background())
		got <- websocket.CloseStatus(err)
	})
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	c := b.open(LabelWS)
	c.sendJSON(t, KindHead, RequestHead{Path: "/ws-hold", Headers: []Header{}})
	if f := c.next(t); f.Kind != KindHead {
		t.Fatalf("got %#x", f.Kind)
	}
	c.sendJSON(t, KindClose, Close{Code: 4000, Reason: "the browser went"})
	if f := c.next(t); f.Kind != KindClose || !strings.Contains(string(f.Payload), "4000") {
		t.Fatalf("close answered %#x %s", f.Kind, f.Payload)
	}
	select {
	case code := <-got:
		if code != 4000 {
			t.Fatalf("the desk saw %d", code)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the desk saw no close")
	}
}
