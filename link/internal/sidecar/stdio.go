package sidecar

import (
	"bufio"
	"encoding/json"
	"errors"
	"io"
	"sync"
)

// The stdio protocol between the desk's JVM and the sidecar (docs/cloud-engineering.md §"The
// sidecar"): the link secret as the first line on stdin, then one JSON object per line each way.

// MaxLineBytes is the longest line either side reads; a longer one is skipped.
const MaxLineBytes = 1 << 20

// ICEServer is a STUN or TURN server, in WebRTC's RTCIceServer shape.
type ICEServer struct {
	URLs       []string `json:"urls"`
	Username   string   `json:"username,omitempty"`
	Credential string   `json:"credential,omitempty"`
}

// ICECandidate is one candidate, in WebRTC's RTCIceCandidateInit shape.
type ICECandidate struct {
	Candidate        string  `json:"candidate"`
	SDPMid           *string `json:"sdpMid,omitempty"`
	SDPMLineIndex    *uint16 `json:"sdpMLineIndex,omitempty"`
	UsernameFragment *string `json:"usernameFragment,omitempty"`
}

// Inbound is a line from the JVM.
type Inbound struct {
	Type       string        `json:"type"`
	SID        string        `json:"sid"`
	Offer      string        `json:"offer,omitempty"`
	ICEServers []ICEServer   `json:"iceServers,omitempty"`
	Candidate  *ICECandidate `json:"candidate,omitempty"`
	Reason     string        `json:"reason,omitempty"`
}

const (
	InSessionOpen  = "session.open"
	InSessionICE   = "session.ice"
	InSessionClose = "session.close"

	OutStarted       = "started"
	OutSessionAnswer = "session.answer"
	OutSessionICE    = "session.ice"
	OutSessionState  = "session.state"
	OutSessionClosed = "session.closed"
)

// Outbound is a line to the JVM.
type Outbound struct {
	Type      string        `json:"type"`
	SID       string        `json:"sid,omitempty"`
	SDP       string        `json:"sdp,omitempty"`
	Candidate *ICECandidate `json:"candidate,omitempty"`
	Path      string        `json:"path,omitempty"`
	Reason    string        `json:"reason,omitempty"`
	Build     string        `json:"build,omitempty"`
}

// Paths a session.state reports: whether the selected candidate pair is relayed through TURN.
const (
	PathDirect = "DIRECT"
	PathRelay  = "RELAY"
)

// ErrLineTooLong is returned by LineReader for a line over MaxLineBytes, which it has skipped.
var ErrLineTooLong = errors.New("a line over the limit")

// LineReader reads newline-terminated lines of at most max bytes.
type LineReader struct {
	r   *bufio.Reader
	max int
}

func NewLineReader(r io.Reader, max int) *LineReader {
	return &LineReader{r: bufio.NewReaderSize(r, 64*1024), max: max}
}

// Next answers the next line without its newline, ErrLineTooLong for one it skipped, or io.EOF.
func (l *LineReader) Next() ([]byte, error) {
	var line []byte
	tooLong := false
	for {
		chunk, err := l.r.ReadSlice('\n')
		if !tooLong {
			if len(line)+len(chunk) > l.max+1 {
				tooLong = true
				line = nil
			} else {
				line = append(line, chunk...)
			}
		}
		switch {
		case err == nil:
			if tooLong {
				return nil, ErrLineTooLong
			}
			return trimEOL(line), nil
		case errors.Is(err, bufio.ErrBufferFull):
			continue
		case errors.Is(err, io.EOF) && len(line) > 0 && !tooLong:
			return trimEOL(line), nil
		default:
			return nil, err
		}
	}
}

func trimEOL(b []byte) []byte {
	if n := len(b); n > 0 && b[n-1] == '\n' {
		b = b[:n-1]
	}
	if n := len(b); n > 0 && b[n-1] == '\r' {
		b = b[:n-1]
	}
	return b
}

// LineWriter writes one JSON object per line, whole lines only, from any goroutine.
type LineWriter struct {
	mu sync.Mutex
	w  *bufio.Writer
}

func NewLineWriter(w io.Writer) *LineWriter { return &LineWriter{w: bufio.NewWriter(w)} }

func (l *LineWriter) Write(message Outbound) error {
	line, err := json.Marshal(message)
	if err != nil {
		return err
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if _, err := l.w.Write(append(line, '\n')); err != nil {
		return err
	}
	return l.w.Flush()
}
