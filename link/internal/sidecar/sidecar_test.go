package sidecar

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestCtlReadyForALinkedSession(t *testing.T) {
	h := newHarness(t, newFakeDesk(), nil)
	b := h.connect("s1")
	got := b.hello("good")
	want := map[string]any{"type": "ready", "user": "chris", "role": "ADMIN", "build": "1.4.0+test"}
	if jsonOf(got) != jsonOf(want) {
		t.Fatalf("hello answered %v, want %v", got, want)
	}
}

func TestCtlReadyForAnUnlinkedSession(t *testing.T) {
	h := newHarness(t, newFakeDesk(), nil)
	got := h.connect("s1").hello("unlinked")
	want := map[string]any{"type": "ready", "unlinked": true, "build": "1.4.0+test"}
	if jsonOf(got) != jsonOf(want) {
		t.Fatalf("hello answered %v, want %v", got, want)
	}
}

func TestCtlRefusalEndsTheSession(t *testing.T) {
	h := newHarness(t, newFakeDesk(), nil)
	got := h.connect("s1").hello("expired")
	if jsonOf(got) != jsonOf(map[string]any{"type": "refused", "code": "TOKEN_EXPIRED"}) {
		t.Fatalf("hello answered %v", got)
	}
	if reason := h.closedReason("s1", 10*time.Second); reason != "TOKEN_EXPIRED" {
		t.Fatalf("closed with %q", reason)
	}
}

// request sends one HTTP exchange on a fresh channel and answers the sidecar's head and body.
func request(t *testing.T, b *browser, head RequestHead, body []byte) (ResponseHead, []byte) {
	t.Helper()
	c := b.open(LabelHTTP)
	c.sendJSON(t, KindHead, head)
	for len(body) > 0 {
		n := min(len(body), MaxChunkBytes)
		c.send(t, EncodeFrame(KindData, body[:n]))
		body = body[n:]
	}
	c.send(t, []byte{KindEnd})
	return readResponse(t, c)
}

func readResponse(t *testing.T, c *testChannel) (ResponseHead, []byte) {
	t.Helper()
	f := c.next(t)
	if f.Kind != KindHead {
		t.Fatalf("expected a HEAD, got %#x %q", f.Kind, f.Payload)
	}
	var head ResponseHead
	if err := json.Unmarshal(f.Payload, &head); err != nil {
		t.Fatal(err)
	}
	var out bytes.Buffer
	for {
		f := c.next(t)
		switch f.Kind {
		case KindData:
			out.Write(f.Payload)
		case KindEnd:
			return head, out.Bytes()
		default:
			t.Fatalf("unexpected frame %#x %q", f.Kind, f.Payload)
		}
	}
}

func headerOf(head ResponseHead, name string) (string, bool) {
	for _, h := range head.Headers {
		if h[0] == name {
			return h[1], true
		}
	}
	return "", false
}

func TestHTTPDropsTheBrowsersCredentialsAndSetsTheSessions(t *testing.T) {
	desk := newFakeDesk()
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	head, body := request(t, b, RequestHead{
		Method: "POST",
		Path:   "/echo?x=1",
		Headers: []Header{
			{"Cookie", "session=stolen"},
			{"authorization", "Bearer forged"},
			{"Lighting7-Link", "forged"},
			{"Origin", "https://elsewhere.example"},
			{"host", "elsewhere.example"},
			{"content-type", "text/plain"},
			{"x-custom", "kept"},
		},
		Origin: "https://inst.desk.cloud.example",
	}, []byte("hello"))
	if head.Status != 200 || string(body) != "hello" {
		t.Fatalf("answered %d %q", head.Status, body)
	}
	if _, ok := headerOf(head, "set-cookie"); ok {
		t.Error("a cookie reached the browser")
	}
	if v, _ := headerOf(head, "content-type"); v != "text/plain" {
		t.Errorf("content-type %q", v)
	}
	r := desk.last()
	if got := r.Header.Values("Cookie"); len(got) != 0 {
		t.Errorf("the desk saw a cookie: %v", got)
	}
	if got := r.Header.Values("Authorization"); len(got) != 1 || got[0] != "Bearer good" {
		t.Errorf("the desk saw Authorization %v", got)
	}
	if got := r.Header.Values(linkHeader); len(got) != 1 || got[0] != testSecret {
		t.Errorf("the desk saw Lighting7-Link %v", got)
	}
	if got := r.Header.Values("Origin"); len(got) != 1 || got[0] != "https://inst.desk.cloud.example" {
		t.Errorf("the desk saw Origin %v", got)
	}
	if r.Host != strings.TrimPrefix(h.desk.URL, "http://") {
		t.Errorf("the desk saw Host %q", r.Host)
	}
	if r.Header.Get("x-custom") != "kept" || r.URL.RawQuery != "x=1" {
		t.Errorf("lost the request's own header or query: %v %q", r.Header, r.URL.RawQuery)
	}
}

func TestARefreshedTokenIsUsedFromTheNextRequest(t *testing.T) {
	desk := newFakeDesk()
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	if err := b.ctl.dc.SendText(`{"type":"token","token":"good-2"}`); err != nil {
		t.Fatal(err)
	}
	deadline := time.Now().Add(10 * time.Second)
	for {
		request(t, b, RequestHead{Method: "GET", Path: "/echo", Headers: []Header{}}, nil)
		if bearer(desk.last()) == "good-2" {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("still sending %q", bearer(desk.last()))
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func TestARefusedRefreshEndsTheSession(t *testing.T) {
	h := newHarness(t, newFakeDesk(), nil)
	b := h.connect("s1")
	b.ready()
	if err := b.ctl.dc.SendText(`{"type":"token","token":"other-account"}`); err != nil {
		t.Fatal(err)
	}
	if got := b.ctl.text(t); got != `{"type":"refused","code":"REMOTE_SESSION_MISMATCH"}` {
		t.Fatalf("refresh answered %s", got)
	}
	if reason := h.closedReason("s1", 10*time.Second); reason != "REMOTE_SESSION_MISMATCH" {
		t.Fatalf("closed with %q", reason)
	}
}

func TestAChannelBeforeTheTokenWaitsThenResets(t *testing.T) {
	h := newHarness(t, newFakeDesk(), func(c *Config) { c.ReadyWait = 300 * time.Millisecond })
	b := h.connect("s1")
	c := b.open(LabelHTTP)
	c.sendJSON(t, KindHead, RequestHead{Method: "GET", Path: "/echo", Headers: []Header{}})
	c.send(t, []byte{KindEnd})
	f := c.next(t)
	if f.Kind != KindReset || !strings.Contains(string(f.Payload), "NOT_READY") {
		t.Fatalf("got %#x %s", f.Kind, f.Payload)
	}
}

func TestWebSocketMessagesBothWaysAndTheDesksClose(t *testing.T) {
	desk := newFakeDesk()
	desk.wsEcho()
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	c := b.open(LabelWS)
	c.sendJSON(t, KindHead, RequestHead{Path: "/ws-echo", Headers: []Header{{"Cookie", "x=1"}, {"Sec-WebSocket-Protocol", "lighting7.v1"}}, Origin: "https://inst.desk.cloud.example"})
	if f := c.next(t); f.Kind != KindHead || jsonOf(json.RawMessage(f.Payload)) != `{"status":101,"headers":[["sec-websocket-protocol","lighting7.v1"]]}` {
		t.Fatalf("upgrade answered %#x %s", f.Kind, f.Payload)
	}
	if r := desk.last(); r.Header.Get("Cookie") != "" || r.Header.Get("Origin") != "https://inst.desk.cloud.example" || bearer(r) != "good" {
		t.Fatalf("the upgrade carried %v", r.Header)
	}

	// A text message in two fragments comes back whole; a 40 KiB one comes back in three.
	c.send(t, EncodeFrame(KindTextPart, []byte(`{"type":"fxS`)))
	c.send(t, EncodeFrame(KindTextEnd, []byte(`tate"}`)))
	if f := c.next(t); f.Kind != KindTextEnd || string(f.Payload) != `{"type":"fxState"}` {
		t.Fatalf("echo %#x %q", f.Kind, f.Payload)
	}
	big := bytes.Repeat([]byte{7}, 40*1024)
	c.send(t, EncodeFrame(KindBinaryEnd, big[:MaxChunkBytes]))
	kinds := []byte{}
	var echoed []byte
	for len(kinds) == 0 || kinds[len(kinds)-1] != KindBinaryEnd {
		f := c.next(t)
		kinds = append(kinds, f.Kind)
		echoed = append(echoed, f.Payload...)
	}
	if !bytes.Equal(echoed, big[:MaxChunkBytes]) || !bytes.Equal(kinds, []byte{KindBinaryEnd}) {
		t.Fatalf("binary echo %d bytes in %v", len(echoed), kinds)
	}
	c.send(t, EncodeFrame(KindBinaryPart, big[:MaxChunkBytes]))
	c.send(t, EncodeFrame(KindBinaryPart, big[MaxChunkBytes:2*MaxChunkBytes]))
	c.send(t, EncodeFrame(KindBinaryEnd, big[2*MaxChunkBytes:]))
	kinds, echoed = nil, nil
	for len(kinds) == 0 || kinds[len(kinds)-1] != KindBinaryEnd {
		f := c.next(t)
		kinds = append(kinds, f.Kind)
		echoed = append(echoed, f.Payload...)
	}
	if !bytes.Equal(echoed, big) || !bytes.Equal(kinds, []byte{KindBinaryPart, KindBinaryPart, KindBinaryEnd}) {
		t.Fatalf("binary echo %d bytes in %v", len(echoed), kinds)
	}
	c.send(t, EncodeFrame(KindTextEnd, nil))
	if f := c.next(t); f.Kind != KindTextEnd || len(f.Payload) != 0 {
		t.Fatalf("empty echo %#x %q", f.Kind, f.Payload)
	}

	c.send(t, EncodeFrame(KindTextEnd, []byte("bye")))
	f := c.next(t)
	if f.Kind != KindClose || jsonOf(json.RawMessage(f.Payload)) != `{"code":4401,"reason":"session ended"}` {
		t.Fatalf("close %#x %s", f.Kind, f.Payload)
	}
	c.sendJSON(t, KindClose, Close{Code: 4401})
	select {
	case <-c.closed:
	case <-time.After(10 * time.Second):
		t.Fatal("the channel stayed open after both closes")
	}
}

func TestARefusedUpgradeAnswersItsStatus(t *testing.T) {
	desk := newFakeDesk()
	desk.mux.HandleFunc("/api", func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(http.StatusForbidden) })
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	c := b.open(LabelWS)
	c.sendJSON(t, KindHead, RequestHead{Path: "/api", Headers: []Header{}, Origin: "https://elsewhere.example"})
	if f := c.next(t); f.Kind != KindHead || jsonOf(json.RawMessage(f.Payload)) != `{"status":403,"headers":[]}` {
		t.Fatalf("answered %#x %s", f.Kind, f.Payload)
	}
	select {
	case <-c.closed:
	case <-time.After(10 * time.Second):
		t.Fatal("the channel stayed open")
	}
}

func TestTheSessionLimit(t *testing.T) {
	h := newHarness(t, newFakeDesk(), func(c *Config) { c.MaxSessions = 2 })
	h.connect("s1")
	h.connect("s2")
	if _, answered := h.offer("s3"); answered {
		t.Fatal("a third session was answered")
	}
	if reason := h.closedReason("s3", 5*time.Second); reason != CodeSessionLimit {
		t.Fatalf("closed with %q", reason)
	}
}

func TestTheChannelLimit(t *testing.T) {
	desk := newFakeDesk()
	release := make(chan struct{})
	desk.mux.HandleFunc("/hold", func(w http.ResponseWriter, r *http.Request) { <-release })
	h := newHarness(t, desk, func(c *Config) { c.MaxChannels = 2 })
	t.Cleanup(func() { close(release) })
	b := h.connect("s1")
	b.ready()
	for i := 0; i < 2; i++ {
		c := b.open(LabelHTTP)
		c.sendJSON(t, KindHead, RequestHead{Method: "GET", Path: "/hold", Headers: []Header{}})
		c.send(t, []byte{KindEnd})
	}
	time.Sleep(200 * time.Millisecond)
	c := b.open(LabelHTTP)
	c.sendJSON(t, KindHead, RequestHead{Method: "GET", Path: "/echo", Headers: []Header{}})
	if f := c.next(t); f.Kind != KindReset || !strings.Contains(string(f.Payload), "TOO_MANY_CHANNELS") {
		t.Fatalf("a third channel got %#x %s", f.Kind, f.Payload)
	}
}

func TestADirectPathIsReported(t *testing.T) {
	h := newHarness(t, newFakeDesk(), nil)
	h.connect("s1")
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		h.mu.Lock()
		states := append([]string(nil), h.states["s1"]...)
		h.mu.Unlock()
		if len(states) > 0 {
			if states[0] != PathDirect {
				t.Fatalf("reported %v", states)
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("no session.state")
}

func TestTheJVMClosesASession(t *testing.T) {
	h := newHarness(t, newFakeDesk(), nil)
	b := h.connect("s1")
	h.send(Inbound{Type: InSessionClose, SID: "s1", Reason: "TOKEN_REVOKED"})
	if reason := h.closedReason("s1", 5*time.Second); reason != "TOKEN_REVOKED" {
		t.Fatalf("closed with %q", reason)
	}
	select {
	case <-b.ctl.closed:
	case <-time.After(15 * time.Second):
		t.Fatal("the browser's ctl stayed open")
	}
}

func TestLineReaderSkipsALongLine(t *testing.T) {
	in := strings.Repeat("x", 100) + "\nshort\n" + "tail"
	r := NewLineReader(strings.NewReader(in), 10)
	if _, err := r.Next(); err != ErrLineTooLong {
		t.Fatalf("the long line gave %v", err)
	}
	if line, err := r.Next(); err != nil || string(line) != "short" {
		t.Fatalf("then %q %v", line, err)
	}
	if line, err := r.Next(); err != nil || string(line) != "tail" {
		t.Fatalf("then %q %v", line, err)
	}
	if _, err := r.Next(); err != io.EOF {
		t.Fatalf("then %v", err)
	}
}
