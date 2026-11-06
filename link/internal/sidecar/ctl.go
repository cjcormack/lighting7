package sidecar

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"time"
)

// ctl's messages (docs/cloud-protocol.md §"ctl"), JSON text with type naming each.
type ctlIn struct {
	Type  string `json:"type"`
	Token string `json:"token"`
}

type ctlReady struct {
	Type     string `json:"type"`
	User     string `json:"user,omitempty"`
	Role     string `json:"role,omitempty"`
	Unlinked bool   `json:"unlinked,omitempty"`
	Build    string `json:"build"`
}

type ctlRefused struct {
	Type string `json:"type"`
	Code string `json:"code"`
}

// The desk's GET /api/rest/auth/status, as far as ctl reads it.
type authStatus struct {
	Authenticated bool `json:"authenticated"`
	User          *struct {
		Username string `json:"username"`
		Role     string `json:"role"`
	} `json:"user"`
	Unlinked bool `json:"unlinked"`
}

type errorBody struct {
	Code string `json:"code"`
}

const (
	pathAuthStatus   = "/api/rest/auth/status"
	pathSessionToken = "/api/rest/cloud/session/token"
	codeUnauthorized = "unauthenticated"
	ctlCallWait      = 15 * time.Second
	maxCtlBody       = 64 * 1024
)

// runCtl answers ctl's hello by asking the desk about the token, and hands each refreshed token to
// the desk. A refusal ends the session.
func (s *session) runCtl(c *channel) {
	for {
		m, err := c.next()
		if err != nil {
			return
		}
		if !m.IsString {
			continue
		}
		var in ctlIn
		if json.Unmarshal(m.Data, &in) != nil || in.Token == "" {
			continue
		}
		switch in.Type {
		case "hello":
			if s.helloed.CompareAndSwap(false, true) {
				s.hello(c, in.Token)
			}
		case "token":
			if s.helloed.Load() {
				s.refresh(c, in.Token)
			}
		}
	}
}

func (s *session) hello(c *channel, token string) {
	status, body, err := s.ask(http.MethodGet, pathAuthStatus, token)
	if err != nil {
		s.refuse(c, codeUnauthorized)
		return
	}
	if status != http.StatusOK {
		s.refuse(c, codeOf(body))
		return
	}
	var auth authStatus
	if json.Unmarshal(body, &auth) != nil {
		s.refuse(c, codeUnauthorized)
		return
	}
	ready := ctlReady{Type: "ready", Build: s.sc.cfg.Build}
	switch {
	case auth.Authenticated && auth.User != nil:
		ready.User, ready.Role = auth.User.Username, auth.User.Role
	case auth.Unlinked:
		ready.Unlinked = true
	default:
		s.refuse(c, codeUnauthorized)
		return
	}
	s.setToken(token)
	if text, err := json.Marshal(ready); err == nil {
		_ = c.sendText(string(text))
	}
}

func (s *session) refresh(c *channel, token string) {
	status, body, err := s.ask(http.MethodPost, pathSessionToken, token)
	if err != nil {
		s.refuse(c, codeUnauthorized)
		return
	}
	if status != http.StatusNoContent && status != http.StatusOK {
		s.refuse(c, codeOf(body))
		return
	}
	s.setToken(token)
}

func (s *session) refuse(c *channel, code string) {
	if text, err := json.Marshal(ctlRefused{Type: "refused", Code: code}); err == nil {
		_ = c.sendText(string(text))
	}
	c.close()
	s.close(code)
}

// ask calls the desk as the session with token, answering its status and at most maxCtlBody of its body.
func (s *session) ask(method, path, token string) (int, []byte, error) {
	ctx, cancel := context.WithTimeout(s.ctx, ctlCallWait)
	defer cancel()
	req, err := http.NewRequestWithContext(ctx, method, s.sc.cfg.Target.String()+path, nil)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set(linkHeader, s.sc.cfg.Secret)
	req.Header.Set("Accept", "application/json")
	resp, err := s.sc.client.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxCtlBody))
	return resp.StatusCode, body, err
}

func codeOf(body []byte) string {
	var e errorBody
	if json.Unmarshal(body, &e) == nil && e.Code != "" && len(e.Code) <= 64 {
		return e.Code
	}
	return codeUnauthorized
}

const linkHeader = "Lighting7-Link"
