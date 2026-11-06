package sidecar

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"github.com/pion/webrtc/v4"
)

// session is one remote session: a peer connection and the data channels the browser opens on it.
type session struct {
	sc     *Sidecar
	sid    string
	ctx    context.Context
	cancel context.CancelFunc

	pc        *webrtc.PeerConnection
	pcReady   chan struct{}
	closeOnce sync.Once

	tokenMu sync.RWMutex
	token   string
	ready   chan struct{}
	readyOK sync.Once
	helloed atomic.Bool
	hasCtl  atomic.Bool

	channels atomic.Int32
	path     atomic.Value

	// The browser's candidates, applied in order once the peer connection exists.
	remote chan ICECandidate

	// The sidecar's own candidates wait for the answer, which must reach the browser first.
	localMu  sync.Mutex
	answered bool
	held     []Outbound
}

// remoteCandidates is how many of the browser's candidates a session holds before dropping one.
const remoteCandidates = 64

func newSession(parent context.Context, sc *Sidecar, sid string) *session {
	ctx, cancel := context.WithCancel(parent)
	s := &session{
		sc: sc, sid: sid, ctx: ctx, cancel: cancel,
		pcReady: make(chan struct{}), ready: make(chan struct{}), remote: make(chan ICECandidate, remoteCandidates),
	}
	go s.applyCandidates()
	return s
}

// emitLocal sends one of the sidecar's candidate lines, or holds it until the answer has gone.
func (s *session) emitLocal(out Outbound) {
	s.localMu.Lock()
	defer s.localMu.Unlock()
	if !s.answered {
		s.held = append(s.held, out)
		return
	}
	s.sc.emit(out)
}

// emitAnswer sends the answer, then the candidates gathered before it, in order.
func (s *session) emitAnswer(sdp string) {
	s.localMu.Lock()
	defer s.localMu.Unlock()
	s.sc.emit(Outbound{Type: OutSessionAnswer, SID: s.sid, SDP: sdp})
	s.answered = true
	for _, out := range s.held {
		s.sc.emit(out)
	}
	s.held = nil
}

func (s *session) start(in Inbound) {
	defer close(s.pcReady)
	servers := make([]webrtc.ICEServer, 0, len(in.ICEServers))
	for _, server := range in.ICEServers {
		servers = append(servers, webrtc.ICEServer{URLs: server.URLs, Username: server.Username, Credential: server.Credential})
	}
	pc, err := s.sc.api.NewPeerConnection(webrtc.Configuration{ICEServers: servers})
	if err != nil {
		s.sc.cfg.Log.Printf("session %s: couldn't make a peer connection: %v", s.sid, err)
		s.close(CodeMalformed)
		return
	}
	s.pc = pc
	pc.OnICECandidate(func(c *webrtc.ICECandidate) {
		if s.ctx.Err() != nil {
			return
		}
		out := Outbound{Type: OutSessionICE, SID: s.sid}
		if c != nil {
			init := c.ToJSON()
			out.Candidate = &ICECandidate{Candidate: init.Candidate, SDPMid: init.SDPMid, SDPMLineIndex: init.SDPMLineIndex, UsernameFragment: init.UsernameFragment}
		}
		s.emitLocal(out)
	})
	pc.OnConnectionStateChange(func(state webrtc.PeerConnectionState) {
		switch state {
		case webrtc.PeerConnectionStateConnected:
			s.reportPath()
		case webrtc.PeerConnectionStateFailed, webrtc.PeerConnectionStateClosed:
			s.close(CodeSessionEnded)
		}
	})
	if sctp := pc.SCTP(); sctp != nil && sctp.Transport() != nil && sctp.Transport().ICETransport() != nil {
		sctp.Transport().ICETransport().OnSelectedCandidatePairChange(func(*webrtc.ICECandidatePair) { s.reportPath() })
	}
	pc.OnDataChannel(s.accept)

	if err := pc.SetRemoteDescription(webrtc.SessionDescription{Type: webrtc.SDPTypeOffer, SDP: in.Offer}); err != nil {
		s.sc.cfg.Log.Printf("session %s: the offer was refused: %v", s.sid, err)
		s.close(CodeMalformed)
		return
	}
	answer, err := pc.CreateAnswer(nil)
	if err == nil {
		err = pc.SetLocalDescription(answer)
	}
	if err != nil {
		s.sc.cfg.Log.Printf("session %s: couldn't answer: %v", s.sid, err)
		s.close(CodeMalformed)
		return
	}
	s.emitAnswer(pc.LocalDescription().SDP)

	time.AfterFunc(s.sc.cfg.ConnectWait, func() {
		if pc.ConnectionState() != webrtc.PeerConnectionStateConnected {
			s.close(CodeSessionEnded)
		}
	})
}

// addCandidate queues one of the browser's candidates without waiting, so a session still being
// set up holds up no other session's lines.
func (s *session) addCandidate(c *ICECandidate) {
	if c == nil {
		return
	}
	select {
	case s.remote <- *c:
	default:
		s.sc.cfg.Log.Printf("session %s: skipping a candidate past %d queued", s.sid, remoteCandidates)
	}
}

func (s *session) applyCandidates() {
	select {
	case <-s.pcReady:
	case <-s.ctx.Done():
		return
	}
	for {
		select {
		case <-s.ctx.Done():
			return
		case c := <-s.remote:
			if s.pc == nil {
				return
			}
			init := webrtc.ICECandidateInit{Candidate: c.Candidate, SDPMid: c.SDPMid, SDPMLineIndex: c.SDPMLineIndex, UsernameFragment: c.UsernameFragment}
			if err := s.pc.AddICECandidate(init); err != nil {
				s.sc.cfg.Log.Printf("session %s: skipping a candidate: %v", s.sid, err)
			}
		}
	}
}

// reportPath tells the JVM whether the selected candidate pair is relayed, when that changes.
func (s *session) reportPath() {
	pc := s.pc
	if pc == nil || pc.SCTP() == nil || pc.SCTP().Transport() == nil {
		return
	}
	pair, err := pc.SCTP().Transport().ICETransport().GetSelectedCandidatePair()
	if err != nil || pair == nil || pair.Local == nil || pair.Remote == nil {
		return
	}
	path := PathDirect
	if pair.Local.Typ == webrtc.ICECandidateTypeRelay || pair.Remote.Typ == webrtc.ICECandidateTypeRelay {
		path = PathRelay
	}
	if previous, _ := s.path.Swap(path).(string); previous == path {
		return
	}
	s.sc.emit(Outbound{Type: OutSessionState, SID: s.sid, Path: path})
}

func (s *session) accept(dc *webrtc.DataChannel) {
	if s.ctx.Err() != nil {
		_ = dc.Close()
		return
	}
	switch dc.Label() {
	case LabelCtl:
		if !s.hasCtl.CompareAndSwap(false, true) {
			_ = dc.Close()
			return
		}
		c := newChannel(s.ctx, dc)
		go s.runCtl(c)
	case LabelHTTP, LabelWS:
		c := newChannel(s.ctx, dc)
		if s.channels.Add(1) > int32(s.sc.cfg.MaxChannels) {
			s.channels.Add(-1)
			// Answers the exchange's first frame, so a browser that opened the channel is listening.
			go func() {
				if _, err := c.nextOr(after(5 * time.Second)); err == nil {
					_ = c.sendJSON(KindReset, Reset{Reason: "TOO_MANY_CHANNELS"})
				}
				c.close()
			}()
			return
		}
		go func() {
			defer s.channels.Add(-1)
			if dc.Label() == LabelHTTP {
				s.runHTTP(c)
			} else {
				s.runWS(c)
			}
		}()
	default:
		_ = dc.Close()
	}
}

// currentToken is the session's latest accepted token, once ctl has had one accepted.
func (s *session) currentToken() string {
	s.tokenMu.RLock()
	defer s.tokenMu.RUnlock()
	return s.token
}

func (s *session) setToken(token string) {
	s.tokenMu.Lock()
	s.token = token
	s.tokenMu.Unlock()
	s.readyOK.Do(func() { close(s.ready) })
}

// awaitReady waits for ctl's token, up to ReadyWait.
func (s *session) awaitReady(c *channel) bool {
	select {
	case <-s.ready:
		return true
	case <-c.ctx.Done():
		return false
	case <-time.After(s.sc.cfg.ReadyWait):
		return false
	}
}

func (s *session) close(reason string) {
	s.closeOnce.Do(func() {
		if reason == "" {
			reason = CodeSessionEnded
		}
		s.cancel()
		s.sc.remove(s)
		s.sc.emit(Outbound{Type: OutSessionClosed, SID: s.sid, Reason: reason})
		go func() {
			<-s.pcReady
			if s.pc != nil {
				_ = s.pc.Close()
			}
		}()
	})
}
