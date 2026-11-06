package sidecar

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"strings"
	"sync"
	"time"

	"github.com/coder/websocket"
)

// closeAnswerWait is how long a WebSocket the desk closed waits for the browser's own CLOSE.
const closeAnswerWait = 5 * time.Second

// droppedWSHeaders are dropped beside StrippedHeaders: the dialler sets its own.
var droppedWSHeaders = []string{"content-length", "accept-encoding", "sec-websocket-accept", "sec-websocket-protocol"}

// runWS bridges one WebSocket: the upgrade, then each message as fragments both ways, then the close.
func (s *session) runWS(c *channel) {
	defer c.close()
	head, err := readHead(c)
	if err != nil {
		if !errors.Is(err, errChannelClosed) {
			_ = c.sendJSON(KindHead, ResponseHead{Status: 400, Headers: []Header{}})
		}
		return
	}
	if !s.awaitReady(c) {
		_ = c.sendJSON(KindHead, ResponseHead{Status: 401, Headers: []Header{}})
		return
	}
	u, err := s.deskURL("ws", head.Path)
	if err != nil {
		_ = c.sendJSON(KindHead, ResponseHead{Status: 400, Headers: []Header{}})
		return
	}
	ctx, cancel := context.WithCancel(c.ctx)
	defer cancel()
	dialCtx, dialCancel := context.WithTimeout(ctx, 15*time.Second)
	conn, resp, err := websocket.Dial(dialCtx, u.String(), &websocket.DialOptions{
		HTTPClient:      s.sc.client,
		HTTPHeader:      s.headers(head, droppedWSHeaders),
		Subprotocols:    subprotocols(head),
		CompressionMode: websocket.CompressionDisabled,
	})
	dialCancel()
	if err != nil {
		status := 502
		if resp != nil && resp.StatusCode != 101 {
			status = resp.StatusCode
		}
		_ = c.sendJSON(KindHead, ResponseHead{Status: status, Headers: []Header{}})
		return
	}
	conn.SetReadLimit(-1)
	headers := []Header{}
	if p := conn.Subprotocol(); p != "" {
		headers = append(headers, Header{"sec-websocket-protocol", p})
	}
	if c.sendJSON(KindHead, ResponseHead{Status: 101, Headers: headers}) != nil {
		_ = conn.CloseNow()
		return
	}

	b := &wsBridge{c: c, conn: conn, deskClosed: make(chan struct{})}
	go b.fromDesk(ctx)
	b.fromBrowser(ctx)
}

type wsBridge struct {
	c          *channel
	conn       *websocket.Conn
	closeMu    sync.Mutex
	sentClose  bool
	deskClosed chan struct{}
}

// sendClose sends the browser one CLOSE, the first one asked for.
func (b *wsBridge) sendClose(code int, reason string) {
	b.closeMu.Lock()
	defer b.closeMu.Unlock()
	if b.sentClose {
		return
	}
	b.sentClose = true
	_ = b.c.sendJSON(KindClose, Close{Code: code, Reason: reason})
}

// fromDesk forwards each of the desk's messages as fragments of at most MaxChunkBytes, then its close.
func (b *wsBridge) fromDesk(ctx context.Context) {
	defer close(b.deskClosed)
	buf := make([]byte, MaxChunkBytes)
	for {
		typ, r, err := b.conn.Reader(ctx)
		if err != nil {
			b.deskEnded(err)
			return
		}
		part, end := KindTextPart, KindTextEnd
		if typ == websocket.MessageBinary {
			part, end = KindBinaryPart, KindBinaryEnd
		}
		var pending []byte
		for {
			n, ended, err := fill(r, buf)
			if n > 0 {
				if pending != nil {
					if b.c.send(EncodeFrame(part, pending)) != nil {
						_ = b.conn.CloseNow()
						return
					}
				}
				pending = append([]byte(nil), buf[:n]...)
			}
			if ended {
				break
			}
			if err != nil {
				b.deskEnded(err)
				return
			}
		}
		if b.c.send(EncodeFrame(end, pending)) != nil {
			_ = b.conn.CloseNow()
			return
		}
	}
}

func (b *wsBridge) deskEnded(err error) {
	code := int(websocket.CloseStatus(err))
	reason := ""
	var ce websocket.CloseError
	if errors.As(err, &ce) {
		reason = ce.Reason
	}
	if code < 0 {
		code = int(websocket.StatusAbnormalClosure)
	}
	b.sendClose(code, reason)
}

// fromBrowser writes the browser's fragments to the desk as messages, until a CLOSE or the channel ends.
func (b *wsBridge) fromBrowser(ctx context.Context) {
	var w io.WriteCloser
	var wType websocket.MessageType
	stop := make(chan struct{})
	go func() {
		select {
		case <-b.deskClosed:
			time.AfterFunc(closeAnswerWait, func() { close(stop) })
		case <-ctx.Done():
		}
	}()
	for {
		m, err := b.c.nextOr(stop)
		if err != nil {
			_ = b.conn.Close(websocket.StatusGoingAway, "")
			return
		}
		f, err := DecodeFrame(m.Data)
		if err != nil || m.IsString {
			b.protocolError()
			return
		}
		switch f.Kind {
		case KindTextPart, KindTextEnd, KindBinaryPart, KindBinaryEnd:
			t := websocket.MessageText
			if f.Kind == KindBinaryPart || f.Kind == KindBinaryEnd {
				t = websocket.MessageBinary
			}
			if w != nil && wType != t {
				b.protocolError()
				return
			}
			if w == nil {
				if w, err = b.conn.Writer(ctx, t); err != nil {
					return
				}
				wType = t
			}
			if _, err := w.Write(f.Payload); err != nil {
				return
			}
			if f.Kind == KindTextEnd || f.Kind == KindBinaryEnd {
				if err := w.Close(); err != nil {
					return
				}
				w = nil
			}
		case KindClose:
			var cl Close
			_ = json.Unmarshal(f.Payload, &cl)
			code := sendableCode(cl.Code)
			b.sendClose(int(code), cl.Reason)
			// Before the deferred cancel, which would drop the connection mid-handshake.
			_ = b.conn.Close(code, truncate(cl.Reason, 123))
			return
		default:
			b.protocolError()
			return
		}
	}
}

func (b *wsBridge) protocolError() {
	b.sendClose(int(websocket.StatusProtocolError), "")
	_ = b.conn.Close(websocket.StatusProtocolError, "")
}

// sendableCode is code if a close frame may carry it, else a normal closure.
func sendableCode(code int) websocket.StatusCode {
	switch {
	case code >= 1000 && code <= 1003, code >= 1007 && code <= 1014, code >= 3000 && code <= 4999:
		return websocket.StatusCode(code)
	default:
		return websocket.StatusNormalClosure
	}
}

// truncate cuts s to at most n bytes on a rune boundary.
func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	for n > 0 && (s[n]&0xC0) == 0x80 {
		n--
	}
	return s[:n]
}

// subprotocols are the protocols the browser's head asks for, which the dialler negotiates.
func subprotocols(head RequestHead) []string {
	var out []string
	for _, kv := range head.Headers {
		if strings.EqualFold(kv[0], "sec-websocket-protocol") {
			for _, p := range strings.Split(kv[1], ",") {
				if p = strings.TrimSpace(p); p != "" {
					out = append(out, p)
				}
			}
		}
	}
	return out
}
