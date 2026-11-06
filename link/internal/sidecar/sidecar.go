// Package sidecar is lighting7-link, the desk's WebRTC sidecar (docs/cloud-engineering.md §"The
// sidecar"). The desk's JVM starts it and speaks to it over its stdio; each remote session is one
// peer connection, whose data channels it bridges to the desk's public listener as the link framing
// says. It decides nothing: the desk judges every request by the token and secret it attaches.
package sidecar

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"net/url"
	"sync"
	"time"

	"github.com/pion/logging"
	"github.com/pion/webrtc/v4"
)

// Config is what one launch of the sidecar runs with.
type Config struct {
	// Target is the desk's public listener, http://host:port.
	Target *url.URL
	// Build is the desk's build, which ctl's ready names.
	Build string
	// Secret is the launch's Lighting7-Link secret, sent on every request to Target.
	Secret string
	// MaxSessions is the most peer connections open at once; the desk opens at most 8.
	MaxSessions int
	// MaxChannels is the most http and ws channels one session has open at once.
	MaxChannels int
	// ReadyWait is how long an http or ws channel waits for ctl to have a token accepted.
	ReadyWait time.Duration
	// ConnectWait is how long a session may take to connect.
	ConnectWait time.Duration
	// Log is where the sidecar logs; stderr in production.
	Log *log.Logger
	// SettingEngine configures pion; tests use it to keep to loopback.
	SettingEngine *webrtc.SettingEngine
}

const (
	DefaultMaxSessions = 16
	DefaultMaxChannels = 128
)

// Sidecar runs the sessions the JVM opens.
type Sidecar struct {
	cfg      Config
	out      *LineWriter
	api      *webrtc.API
	client   *http.Client
	mu       sync.Mutex
	sessions map[string]*session
	wg       sync.WaitGroup
}

// New makes a sidecar that writes its stdio lines to out.
func New(cfg Config, out io.Writer) *Sidecar {
	if cfg.MaxSessions <= 0 {
		cfg.MaxSessions = DefaultMaxSessions
	}
	if cfg.MaxChannels <= 0 {
		cfg.MaxChannels = DefaultMaxChannels
	}
	if cfg.ReadyWait <= 0 {
		cfg.ReadyWait = 15 * time.Second
	}
	if cfg.ConnectWait <= 0 {
		cfg.ConnectWait = 60 * time.Second
	}
	if cfg.Log == nil {
		cfg.Log = log.New(io.Discard, "", 0)
	}
	engine := webrtc.SettingEngine{}
	if cfg.SettingEngine != nil {
		engine = *cfg.SettingEngine
	}
	if engine.LoggerFactory == nil {
		// pion's default logs to stdout, which is the JVM's protocol.
		engine.LoggerFactory = &logging.DefaultLoggerFactory{Writer: cfg.Log.Writer(), DefaultLogLevel: logging.LogLevelWarn}
	}
	return &Sidecar{
		cfg:      cfg,
		out:      NewLineWriter(out),
		api:      webrtc.NewAPI(webrtc.WithSettingEngine(engine)),
		client:   newClient(),
		sessions: map[string]*session{},
	}
}

func newClient() *http.Client {
	return &http.Client{
		Transport: &http.Transport{
			Proxy:               nil,
			DisableCompression:  true,
			MaxIdleConnsPerHost: 64,
			IdleConnTimeout:     90 * time.Second,
		},
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}
}

// ReadSecret reads the launch's secret, the first line on stdin.
func ReadSecret(lines *LineReader) (string, error) {
	line, err := lines.Next()
	if err != nil {
		return "", fmt.Errorf("no link secret on stdin: %w", err)
	}
	if len(line) == 0 {
		return "", errors.New("an empty link secret")
	}
	return string(line), nil
}

// Run says it has started, then serves the JVM's lines until stdin ends or ctx is done, and closes
// every session.
func (s *Sidecar) Run(ctx context.Context, lines *LineReader) error {
	ctx, cancel := context.WithCancel(ctx)
	defer func() {
		cancel()
		s.closeAll()
		s.wg.Wait()
	}()
	if err := s.out.Write(Outbound{Type: OutStarted, Build: s.cfg.Build}); err != nil {
		return err
	}
	type read struct {
		line []byte
		err  error
	}
	reads := make(chan read)
	go func() {
		for {
			line, err := lines.Next()
			select {
			case reads <- read{line, err}:
			case <-ctx.Done():
				return
			}
			if err != nil && !errors.Is(err, ErrLineTooLong) {
				return
			}
		}
	}()
	for {
		select {
		case <-ctx.Done():
			return nil
		case r := <-reads:
			switch {
			case errors.Is(r.err, ErrLineTooLong):
				s.cfg.Log.Printf("skipping a line over %d bytes", MaxLineBytes)
			case errors.Is(r.err, io.EOF):
				return nil
			case r.err != nil:
				return r.err
			default:
				s.handle(ctx, r.line)
			}
		}
	}
}

func (s *Sidecar) handle(ctx context.Context, line []byte) {
	var in Inbound
	if err := json.Unmarshal(line, &in); err != nil || in.SID == "" {
		s.cfg.Log.Printf("skipping a line that is not a message")
		return
	}
	switch in.Type {
	case InSessionOpen:
		s.open(ctx, in)
	case InSessionICE:
		if sess := s.find(in.SID); sess != nil {
			sess.addCandidate(in.Candidate)
		}
	case InSessionClose:
		if sess := s.find(in.SID); sess != nil {
			sess.close(in.Reason)
		}
	default:
		s.cfg.Log.Printf("skipping a message of type %q", in.Type)
	}
}

func (s *Sidecar) find(sid string) *session {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sessions[sid]
}

func (s *Sidecar) open(ctx context.Context, in Inbound) {
	s.mu.Lock()
	if _, ok := s.sessions[in.SID]; ok {
		s.mu.Unlock()
		return
	}
	if len(s.sessions) >= s.cfg.MaxSessions {
		s.mu.Unlock()
		s.emit(Outbound{Type: OutSessionClosed, SID: in.SID, Reason: CodeSessionLimit})
		return
	}
	sess := newSession(ctx, s, in.SID)
	s.sessions[in.SID] = sess
	s.mu.Unlock()
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		sess.start(in)
	}()
}

func (s *Sidecar) remove(sess *session) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.sessions[sess.sid] == sess {
		delete(s.sessions, sess.sid)
	}
}

func (s *Sidecar) closeAll() {
	s.mu.Lock()
	all := make([]*session, 0, len(s.sessions))
	for _, sess := range s.sessions {
		all = append(all, sess)
	}
	s.mu.Unlock()
	for _, sess := range all {
		sess.close(CodeSessionEnded)
	}
}

func (s *Sidecar) emit(message Outbound) {
	if err := s.out.Write(message); err != nil {
		s.cfg.Log.Printf("couldn't write to the desk: %v", err)
	}
}

// The ErrorCode strings the sidecar reports in session.closed and RESET.
const (
	CodeSessionEnded = "SESSION_ENDED"
	CodeSessionLimit = "REMOTE_SESSION_LIMIT"
	CodeMalformed    = "MALFORMED"
)
