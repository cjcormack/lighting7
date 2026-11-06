package sidecar

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"strconv"
	"strings"
)

// droppedRequestHeaders are dropped beside StrippedHeaders: the transport sets content-length from
// the head's, and asks for an identity body, since a service worker's Response never decodes one.
var droppedRequestHeaders = []string{"content-length", "accept-encoding"}

// droppedResponseHeaders never reach the browser: hop-by-hop ones, and cookies a remote session cannot use.
var droppedResponseHeaders = []string{
	"connection", "keep-alive", "proxy-connection", "te", "trailer", "transfer-encoding", "upgrade", "set-cookie",
}

// readHead reads a channel's first frame, which must be a HEAD.
func readHead(c *channel) (RequestHead, error) {
	m, err := c.next()
	if err != nil {
		return RequestHead{}, err
	}
	f, err := DecodeFrame(m.Data)
	if err != nil || m.IsString || f.Kind != KindHead {
		return RequestHead{}, errors.New("the first frame is not a HEAD")
	}
	var head RequestHead
	if err := json.Unmarshal(f.Payload, &head); err != nil {
		return RequestHead{}, err
	}
	if !strings.HasPrefix(head.Path, "/") || strings.ContainsAny(head.Path, " \r\n\t") {
		return RequestHead{}, errors.New("a path that is not absolute")
	}
	return head, nil
}

// deskURL is the desk's URL for path, refused if it would leave the desk's host.
func (s *session) deskURL(scheme, path string) (*url.URL, error) {
	target := *s.sc.cfg.Target
	u, err := url.Parse(scheme + "://" + target.Host + path)
	if err != nil {
		return nil, err
	}
	if u.Host != target.Host || u.User != nil {
		return nil, errors.New("a path that leaves the desk")
	}
	return u, nil
}

// headers is head's headers less the stripped ones, with the session's token, the secret and the origin.
func (s *session) headers(head RequestHead, dropped []string) http.Header {
	h := http.Header{}
	for _, kv := range head.Headers {
		if kv[0] == "" || Stripped(kv[0]) || contains(dropped, kv[0]) {
			continue
		}
		h.Add(kv[0], kv[1])
	}
	h.Set("Authorization", "Bearer "+s.currentToken())
	h.Set(linkHeader, s.sc.cfg.Secret)
	if head.Origin != "" {
		h.Set("Origin", head.Origin)
	}
	return h
}

func responseHeaders(h http.Header) []Header {
	out := []Header{}
	for name, values := range h {
		if contains(droppedResponseHeaders, name) {
			continue
		}
		for _, v := range values {
			out = append(out, Header{strings.ToLower(name), v})
		}
	}
	return out
}

func reset(c *channel, reason string) {
	_ = c.sendJSON(KindReset, Reset{Reason: reason})
}

// runHTTP bridges one HTTP request: the browser's head and body to the desk, its answer back.
func (s *session) runHTTP(c *channel) {
	defer c.close()
	head, err := readHead(c)
	if err != nil {
		if !errors.Is(err, errChannelClosed) {
			reset(c, CodeMalformed)
		}
		return
	}
	if head.Method == "" || strings.ContainsAny(head.Method, " \r\n\t/") {
		reset(c, CodeMalformed)
		return
	}
	if !s.awaitReady(c) {
		reset(c, "NOT_READY")
		return
	}
	u, err := s.deskURL("http", head.Path)
	if err != nil {
		reset(c, CodeMalformed)
		return
	}

	// The frame after the head says whether there is a body at all.
	first, err := c.next()
	if err != nil {
		return
	}
	f, err := DecodeFrame(first.Data)
	if err != nil || first.IsString {
		reset(c, CodeMalformed)
		return
	}
	ctx, cancel := context.WithCancel(c.ctx)
	defer cancel()
	var body io.Reader = http.NoBody
	var pw *io.PipeWriter
	switch f.Kind {
	case KindEnd:
	case KindData:
		var pr *io.PipeReader
		pr, pw = io.Pipe()
		body = pr
	case KindReset:
		return
	default:
		reset(c, CodeMalformed)
		return
	}
	req, err := http.NewRequestWithContext(ctx, head.Method, u.String(), body)
	if err != nil {
		reset(c, CodeMalformed)
		return
	}
	req.Header = s.headers(head, droppedRequestHeaders)
	if pw != nil {
		req.ContentLength = -1
		for _, kv := range head.Headers {
			if strings.EqualFold(kv[0], "content-length") {
				if n, err := strconv.ParseInt(kv[1], 10, 64); err == nil && n >= 0 {
					req.ContentLength = n
				}
			}
		}
	}

	// One reader of the channel for the rest of the exchange: the body, then a RESET at any time.
	aborted := make(chan struct{})
	go func() {
		if pw != nil {
			if _, err := pw.Write(f.Payload); err != nil {
				// The desk answered without reading: what else comes is dropped until a RESET or the close.
				pw = nil
			}
		}
		for {
			m, err := c.next()
			if err != nil {
				closePipe(pw, errChannelClosed)
				cancel()
				return
			}
			g, err := DecodeFrame(m.Data)
			switch {
			case err != nil || m.IsString:
				closePipe(pw, errors.New("a malformed frame"))
				cancel()
				return
			case g.Kind == KindReset:
				close(aborted)
				closePipe(pw, errors.New("the browser abandoned the request"))
				cancel()
				return
			case g.Kind == KindData && pw != nil:
				if _, err := pw.Write(g.Payload); err != nil {
					// The desk stopped reading; whatever else comes is dropped until a RESET or the close.
					pw = nil
				}
			case g.Kind == KindEnd && pw != nil:
				_ = pw.Close()
				pw = nil
			}
		}
	}()

	resp, err := s.sc.client.Do(req)
	if err != nil {
		select {
		case <-aborted:
		default:
			reset(c, "DESK_UNREACHABLE")
		}
		return
	}
	defer resp.Body.Close()
	if err := c.sendJSON(KindHead, ResponseHead{Status: resp.StatusCode, Headers: responseHeaders(resp.Header)}); err != nil {
		return
	}
	buf := make([]byte, MaxChunkBytes)
	for {
		n, ended, err := fill(resp.Body, buf)
		if n > 0 {
			if c.send(EncodeFrame(KindData, buf[:n])) != nil {
				return
			}
		}
		if ended {
			_ = c.send([]byte{KindEnd})
			return
		}
		if err != nil {
			select {
			case <-aborted:
			default:
				reset(c, "DESK_UNREACHABLE")
			}
			return
		}
	}
}

func closePipe(pw *io.PipeWriter, err error) {
	if pw != nil {
		_ = pw.CloseWithError(err)
	}
}
