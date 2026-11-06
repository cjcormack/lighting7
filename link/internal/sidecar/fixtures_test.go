package sidecar

import (
	"bytes"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"time"

	"github.com/coder/websocket"
)

// The contract's fixtures, which the desk's cloud-protocol build owns (docs/cloud-protocol.md §"Golden fixtures").
var fixtureRoot = filepath.Join("..", "..", "..", "cloud-protocol", "src", "test", "resources", "protocol", "link")

type fixtureFrame struct {
	From string          `json:"from"`
	Kind string          `json:"kind"`
	JSON json.RawMessage `json:"json"`
	Text *string         `json:"text"`
	Hex  string          `json:"hex"`
	Fill *struct {
		Byte  byte `json:"byte"`
		Count int  `json:"count"`
	} `json:"fill"`
}

func (f fixtureFrame) bytes(t *testing.T) []byte {
	if f.Fill != nil {
		return EncodeFrame(Kinds[f.Kind], bytes.Repeat([]byte{f.Fill.Byte}, f.Fill.Count))
	}
	b, err := hex.DecodeString(f.Hex)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

type framingFixture struct {
	Labels                    map[string]string `json:"labels"`
	Kinds                     map[string]byte   `json:"kinds"`
	MaxChunkBytes             int               `json:"maxChunkBytes"`
	MaxHeadBytes              int               `json:"maxHeadBytes"`
	BufferedHighBytes         int               `json:"bufferedHighBytes"`
	BufferedLowThresholdBytes int               `json:"bufferedLowThresholdBytes"`
	StrippedHeaders           []string          `json:"strippedHeaders"`
	Exchanges                 []struct {
		Name   string         `json:"name"`
		Label  string         `json:"label"`
		Frames []fixtureFrame `json:"frames"`
	} `json:"exchanges"`
}

func readFraming(t *testing.T) framingFixture {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join(fixtureRoot, "framing.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f framingFixture
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	return f
}

func TestTheFramingConstantsAreTheFixtures(t *testing.T) {
	f := readFraming(t)
	if !reflect.DeepEqual(f.Labels, map[string]string{"ctl": LabelCtl, "http": LabelHTTP, "ws": LabelWS}) {
		t.Errorf("labels %v", f.Labels)
	}
	if !reflect.DeepEqual(f.Kinds, Kinds) {
		t.Errorf("kinds %v, want %v", f.Kinds, Kinds)
	}
	if f.MaxChunkBytes != MaxChunkBytes || f.MaxHeadBytes != MaxHeadBytes || f.BufferedHighBytes != BufferedHighBytes ||
		f.BufferedLowThresholdBytes != BufferedLowThresholdBytes {
		t.Errorf("sizes %+v", f)
	}
	if !reflect.DeepEqual(f.StrippedHeaders, StrippedHeaders) {
		t.Errorf("stripped headers %v", f.StrippedHeaders)
	}
}

// Every fixture frame decodes, and the sidecar's own JSON frames encode to the fixture's bytes.
func TestEveryFixtureFrameDecodesAndEncodes(t *testing.T) {
	for _, ex := range readFraming(t).Exchanges {
		for i, fr := range ex.Frames {
			b := fr.bytes(t)
			frame, err := DecodeFrame(b)
			if err != nil {
				t.Fatalf("%s frame %d: %v", ex.Name, i, err)
			}
			if frame.Kind != Kinds[fr.Kind] {
				t.Fatalf("%s frame %d: kind %#x", ex.Name, i, frame.Kind)
			}
			if fr.Text != nil && string(frame.Payload) != *fr.Text {
				t.Errorf("%s frame %d: text %q", ex.Name, i, frame.Payload)
			}
			if fr.JSON == nil {
				continue
			}
			var v any
			switch {
			case frame.Kind == KindHead && fr.From == "browser":
				v = &RequestHead{}
			case frame.Kind == KindHead:
				v = &ResponseHead{}
			case frame.Kind == KindReset:
				v = &Reset{}
			case frame.Kind == KindClose:
				v = &Close{}
			}
			if err := json.Unmarshal(frame.Payload, v); err != nil {
				t.Fatalf("%s frame %d: %v", ex.Name, i, err)
			}
			again, err := EncodeJSONFrame(frame.Kind, v)
			if err != nil || !bytes.Equal(again, b) {
				t.Errorf("%s frame %d: re-encoded as %x", ex.Name, i, again)
			}
		}
	}
}

func TestCtlFixtures(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join(fixtureRoot, "ctl.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		Label    string `json:"label"`
		Messages []struct {
			From    string          `json:"from"`
			Message json.RawMessage `json:"message"`
		} `json:"messages"`
	}
	if err := json.Unmarshal(raw, &f); err != nil || f.Label != LabelCtl {
		t.Fatalf("%v %q", err, f.Label)
	}
	for _, m := range f.Messages {
		var probe struct{ Type string }
		_ = json.Unmarshal(m.Message, &probe)
		var v any
		switch probe.Type {
		case "hello", "token":
			v = &ctlIn{}
		case "ready":
			v = &ctlReady{}
		case "refused":
			v = &ctlRefused{}
		default:
			t.Fatalf("a ctl message %s", m.Message)
		}
		if err := json.Unmarshal(m.Message, v); err != nil {
			t.Fatal(err)
		}
		again, _ := json.Marshal(v)
		var want, got any
		_ = json.Unmarshal(m.Message, &want)
		_ = json.Unmarshal(again, &got)
		if !reflect.DeepEqual(want, got) {
			t.Errorf("%s re-encoded as %s", m.Message, again)
		}
	}
}

// fixtureDesk answers each exchange as the fixture's desk does.
func fixtureDesk(t *testing.T, aborted chan<- struct{}) *fakeDesk {
	d := newFakeDesk()
	d.mux.HandleFunc("/api/rest/projects/3/prompt-book/pdf", func(w http.ResponseWriter, r *http.Request) {
		d.record(r)
		body, _ := io.ReadAll(r.Body)
		if len(body) != 20000 || strings.Trim(string(body), "%") != "" || r.ContentLength != 20000 {
			t.Errorf("the desk read %d bytes, length %d", len(body), r.ContentLength)
		}
		w.WriteHeader(http.StatusNoContent)
	})
	d.mux.HandleFunc("/api/rest/projects/3/scene-images/ab12", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		_, _ = w.Write([]byte{0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0x00})
	})
	d.mux.HandleFunc("/api/rest/projects/3/scene-images", func(w http.ResponseWriter, r *http.Request) {
		if _, err := io.ReadAll(r.Body); err != nil {
			close(aborted)
		}
	})
	d.mux.HandleFunc("/api", func(w http.ResponseWriter, r *http.Request) {
		d.record(r)
		if r.Header.Get("Origin") != "https://inst-01jbx3q5z8.desk.cloud.example" {
			w.WriteHeader(http.StatusForbidden)
			return
		}
		conn, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
		if err != nil {
			return
		}
		ctx := r.Context()
		_ = conn.Write(ctx, websocket.MessageText, []byte(`{"type":"universesState","universes":[]}`))
		if _, data, err := conn.Read(ctx); err != nil || string(data) != `{"type":"fxState"}` {
			t.Errorf("the desk read %q %v", data, err)
		}
		_ = conn.Write(ctx, websocket.MessageText, nil)
		_ = conn.Close(4401, "session ended")
	})
	return d
}

// Each fixture exchange through the bridge: the browser's frames sent as they are, the sidecar's
// compared with the fixture's, its HEAD's headers apart (the desk adds its own).
func TestTheFixtureExchangesThroughTheBridge(t *testing.T) {
	aborted := make(chan struct{})
	desk := fixtureDesk(t, aborted)
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	for _, ex := range readFraming(t).Exchanges {
		t.Run(ex.Name, func(t *testing.T) {
			c := b.open(ex.Label)
			for _, fr := range ex.Frames {
				if fr.From == "browser" {
					c.send(t, fr.bytes(t))
					continue
				}
				got := c.next(t)
				want, _ := DecodeFrame(fr.bytes(t))
				if got.Kind != want.Kind {
					t.Fatalf("got %#x %q, want %#x %q", got.Kind, got.Payload, want.Kind, want.Payload)
				}
				if got.Kind == KindHead {
					var g, w ResponseHead
					_ = json.Unmarshal(got.Payload, &g)
					_ = json.Unmarshal(want.Payload, &w)
					if g.Status != w.Status {
						t.Fatalf("status %d, want %d", g.Status, w.Status)
					}
					for _, header := range w.Headers {
						if v, _ := headerOf(g, header[0]); v != header[1] {
							t.Errorf("header %s %q, want %q", header[0], v, header[1])
						}
					}
				} else if !bytes.Equal(got.Payload, want.Payload) {
					t.Fatalf("got %q, want %q", got.Payload, want.Payload)
				}
			}
			if ex.Name == "reset" {
				select {
				case <-aborted:
				case <-time.After(10 * time.Second):
					t.Fatal("the desk's request was not abandoned")
				}
			}
			select {
			case <-c.closed:
			case <-time.After(10 * time.Second):
				t.Fatal("the channel stayed open")
			}
		})
	}
}
