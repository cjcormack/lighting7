package sidecar

import (
	"bufio"
	"context"
	"encoding/json"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/ice/v4"
	"github.com/pion/webrtc/v4"
)

const testSecret = "the-link-secret"

// loopbackEngine keeps a peer to UDP over loopback, with no mDNS.
func loopbackEngine() *webrtc.SettingEngine {
	se := &webrtc.SettingEngine{}
	se.SetIncludeLoopbackCandidate(true)
	se.SetIPFilter(func(ip net.IP) bool { return ip.IsLoopback() })
	se.SetNetworkTypes([]webrtc.NetworkType{webrtc.NetworkTypeUDP4})
	se.SetICEMulticastDNSMode(ice.MulticastDNSModeDisabled)
	return se
}

// harness runs a sidecar in this process against desk, speaking its stdio protocol as the JVM would.
type harness struct {
	t      *testing.T
	desk   *httptest.Server
	sc     *Sidecar
	stdin  *io.PipeWriter
	inMu   sync.Mutex
	cancel context.CancelFunc
	done   chan error

	mu      sync.Mutex
	answers map[string]chan string
	closed  map[string]chan string
	states  map[string][]string
	peers   map[string]*browser
	started chan string
	// answered records each session whose answer has been written: no candidate may come before it.
	answered map[string]bool
}

func newHarness(t *testing.T, desk http.Handler, tweak func(*Config)) *harness {
	t.Helper()
	server := httptest.NewServer(desk)
	target, _ := url.Parse(server.URL)
	cfg := Config{Target: target, Build: "1.4.0+test", Secret: testSecret, SettingEngine: loopbackEngine(), Log: log.New(os.Stderr, "sidecar: ", log.Lmicroseconds)}
	if tweak != nil {
		tweak(&cfg)
	}
	inR, inW := io.Pipe()
	outR, outW := io.Pipe()
	h := &harness{
		t: t, desk: server, stdin: inW, done: make(chan error, 1),
		answers: map[string]chan string{}, closed: map[string]chan string{}, states: map[string][]string{},
		peers: map[string]*browser{}, started: make(chan string, 1), answered: map[string]bool{},
	}
	h.sc = New(cfg, outW)
	ctx, cancel := context.WithCancel(context.Background())
	h.cancel = cancel
	lines := NewLineReader(inR, MaxLineBytes)
	go func() {
		h.done <- h.sc.Run(ctx, lines)
		_ = outW.Close()
	}()
	go h.read(outR)
	select {
	case <-h.started:
	case <-time.After(5 * time.Second):
		t.Fatal("the sidecar never said it started")
	}
	t.Cleanup(func() {
		for _, b := range h.allPeers() {
			_ = b.pc.Close()
		}
		_ = inW.Close()
		select {
		case <-h.done:
		case <-time.After(10 * time.Second):
			t.Error("the sidecar did not stop when stdin ended")
		}
		cancel()
		server.Close()
	})
	return h
}

func (h *harness) allPeers() []*browser {
	h.mu.Lock()
	defer h.mu.Unlock()
	out := []*browser{}
	for _, b := range h.peers {
		out = append(out, b)
	}
	return out
}

func (h *harness) send(in Inbound) {
	line, _ := json.Marshal(in)
	h.inMu.Lock()
	defer h.inMu.Unlock()
	if _, err := h.stdin.Write(append(line, '\n')); err != nil {
		h.t.Errorf("couldn't write to the sidecar: %v", err)
	}
}

func (h *harness) chanFor(m map[string]chan string, sid string) chan string {
	h.mu.Lock()
	defer h.mu.Unlock()
	c, ok := m[sid]
	if !ok {
		c = make(chan string, 4)
		m[sid] = c
	}
	return c
}

func (h *harness) read(r io.Reader) {
	scanner := bufio.NewScanner(r)
	scanner.Buffer(make([]byte, 64*1024), MaxLineBytes)
	for scanner.Scan() {
		var out Outbound
		if err := json.Unmarshal(scanner.Bytes(), &out); err != nil {
			h.t.Errorf("a line that is not a message: %q", scanner.Text())
			continue
		}
		switch out.Type {
		case OutStarted:
			h.started <- out.Build
		case OutSessionAnswer:
			h.mu.Lock()
			h.answered[out.SID] = true
			h.mu.Unlock()
			h.chanFor(h.answers, out.SID) <- out.SDP
		case OutSessionICE:
			h.mu.Lock()
			answered := h.answered[out.SID]
			h.mu.Unlock()
			if !answered {
				h.t.Errorf("session %s: a candidate before the answer", out.SID)
			}
			h.mu.Lock()
			b := h.peers[out.SID]
			h.mu.Unlock()
			if b != nil && out.Candidate != nil {
				_ = b.pc.AddICECandidate(webrtc.ICECandidateInit{Candidate: out.Candidate.Candidate, SDPMid: out.Candidate.SDPMid, SDPMLineIndex: out.Candidate.SDPMLineIndex})
			}
		case OutSessionState:
			h.mu.Lock()
			h.states[out.SID] = append(h.states[out.SID], out.Path)
			h.mu.Unlock()
		case OutSessionClosed:
			h.chanFor(h.closed, out.SID) <- out.Reason
		default:
			h.t.Errorf("an unexpected line: %q", scanner.Text())
		}
	}
}

func (h *harness) closedReason(sid string, within time.Duration) string {
	select {
	case r := <-h.chanFor(h.closed, sid):
		return r
	case <-time.After(within):
		h.t.Fatalf("session %s was not closed within %v", sid, within)
		return ""
	}
}

// browser is the shell's side of a session: a pion peer that opens channels as the shell would.
type browser struct {
	t   *testing.T
	h   *harness
	sid string
	pc  *webrtc.PeerConnection
	ctl *testChannel
}

// connect opens session sid in the sidecar and waits until its ctl channel is open.
func (h *harness) connect(sid string) *browser {
	h.t.Helper()
	b, answered := h.offer(sid)
	if !answered {
		h.t.Fatalf("session %s was not answered", sid)
	}
	select {
	case <-b.ctl.opened:
	case <-time.After(15 * time.Second):
		h.t.Fatalf("session %s never connected", sid)
	}
	return b
}

// offer sends session.open for a fresh peer, and reports whether the sidecar answered it.
func (h *harness) offer(sid string) (*browser, bool) {
	h.t.Helper()
	api := webrtc.NewAPI(webrtc.WithSettingEngine(*loopbackEngine()))
	pc, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		h.t.Fatal(err)
	}
	b := &browser{t: h.t, h: h, sid: sid, pc: pc}
	h.mu.Lock()
	h.peers[sid] = b
	h.mu.Unlock()
	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		in := Inbound{Type: InSessionICE, SID: sid}
		if c != nil {
			init := c.ToJSON()
			in.Candidate = &ICECandidate{Candidate: init.Candidate, SDPMid: init.SDPMid, SDPMLineIndex: init.SDPMLineIndex}
		}
		h.send(in)
	})
	b.ctl = b.wrap(must(pc.CreateDataChannel(LabelCtl, nil)))
	offer := must(pc.CreateOffer(nil))
	if err := pc.SetLocalDescription(offer); err != nil {
		h.t.Fatal(err)
	}
	h.send(Inbound{Type: InSessionOpen, SID: sid, Offer: pc.LocalDescription().SDP})
	select {
	case sdp := <-h.chanFor(h.answers, sid):
		if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeAnswer, SDP: sdp}); err != nil {
			h.t.Fatal(err)
		}
		return b, true
	case reason := <-h.chanFor(h.closed, sid):
		h.chanFor(h.closed, sid) <- reason
		return b, false
	case <-time.After(10 * time.Second):
		h.t.Fatalf("session %s: no answer", sid)
		return b, false
	}
}

func must[T any](v T, err error) T {
	if err != nil {
		panic(err)
	}
	return v
}

// testChannel is the browser's end of one data channel.
type testChannel struct {
	dc     *webrtc.DataChannel
	opened chan struct{}
	closed chan struct{}
	in     chan webrtc.DataChannelMessage
	gate   atomic.Pointer[chan struct{}]
	low    chan struct{}
	// sink, when set, takes every message in place of in.
	sink atomic.Pointer[func(webrtc.DataChannelMessage)]
}

func (b *browser) wrap(dc *webrtc.DataChannel) *testChannel {
	c := &testChannel{dc: dc, opened: make(chan struct{}), closed: make(chan struct{}), in: make(chan webrtc.DataChannelMessage, 1<<16), low: make(chan struct{}, 1)}
	dc.OnOpen(func() { close(c.opened) })
	dc.OnClose(func() { close(c.closed) })
	dc.SetBufferedAmountLowThreshold(BufferedLowThresholdBytes)
	dc.OnBufferedAmountLow(func() {
		select {
		case c.low <- struct{}{}:
		default:
		}
	})
	dc.OnMessage(func(m webrtc.DataChannelMessage) {
		if g := c.gate.Load(); g != nil {
			<-*g
		}
		if f := c.sink.Load(); f != nil {
			(*f)(m)
			return
		}
		c.in <- m
	})
	return c
}

// open opens a channel labelled label and waits for it.
func (b *browser) open(label string) *testChannel {
	b.t.Helper()
	c := b.wrap(must(b.pc.CreateDataChannel(label, nil)))
	select {
	case <-c.opened:
	case <-time.After(10 * time.Second):
		b.t.Fatalf("channel %s never opened", label)
	}
	return c
}

// send sends a frame, holding back above the high-water mark as the shell does.
func (c *testChannel) send(t *testing.T, frame []byte) {
	for c.dc.BufferedAmount() > BufferedHighBytes {
		select {
		case <-c.low:
		case <-time.After(100 * time.Millisecond):
		}
	}
	if err := c.dc.Send(frame); err != nil {
		t.Fatalf("send: %v", err)
	}
}

func (c *testChannel) sendJSON(t *testing.T, kind byte, v any) {
	frame, err := EncodeJSONFrame(kind, v)
	if err != nil {
		t.Fatal(err)
	}
	c.send(t, frame)
}

func (c *testChannel) next(t *testing.T) Frame {
	t.Helper()
	select {
	case m := <-c.in:
		f, err := DecodeFrame(m.Data)
		if err != nil {
			t.Fatalf("a bad frame from the sidecar: %v", err)
		}
		return f
	case <-time.After(15 * time.Second):
		t.Fatal("no frame from the sidecar")
		return Frame{}
	}
}

func (c *testChannel) text(t *testing.T) string {
	t.Helper()
	select {
	case m := <-c.in:
		if !m.IsString {
			t.Fatalf("expected text, got %d bytes", len(m.Data))
		}
		return string(m.Data)
	case <-time.After(15 * time.Second):
		t.Fatal("no message on ctl")
		return ""
	}
}

// hello sends ctl's hello and answers what came back.
func (b *browser) hello(token string) map[string]any {
	b.t.Helper()
	text, _ := json.Marshal(map[string]string{"type": "hello", "token": token})
	if err := b.ctl.dc.SendText(string(text)); err != nil {
		b.t.Fatal(err)
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(b.ctl.text(b.t)), &out); err != nil {
		b.t.Fatal(err)
	}
	return out
}

func (b *browser) ready() {
	b.t.Helper()
	if got := b.hello("good"); got["type"] != "ready" {
		b.t.Fatalf("hello was answered %v", got)
	}
}
