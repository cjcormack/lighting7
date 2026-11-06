package sidecar

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// The stdio lines both ends read (link/testdata/stdio.json): each decodes and re-encodes as itself.
func TestTheStdioFixture(t *testing.T) {
	raw, err := os.ReadFile(filepath.Join("..", "..", "testdata", "stdio.json"))
	if err != nil {
		t.Fatal(err)
	}
	var f struct {
		ToSidecar   []json.RawMessage `json:"toSidecar"`
		FromSidecar []json.RawMessage `json:"fromSidecar"`
	}
	if err := json.Unmarshal(raw, &f); err != nil {
		t.Fatal(err)
	}
	same := func(line json.RawMessage, v any) {
		if err := json.Unmarshal(line, v); err != nil {
			t.Fatal(err)
		}
		again, _ := json.Marshal(v)
		var want, got any
		_ = json.Unmarshal(line, &want)
		_ = json.Unmarshal(again, &got)
		if !reflect.DeepEqual(want, got) {
			t.Errorf("%s re-encoded as %s", line, again)
		}
	}
	types := map[string]bool{}
	for _, line := range f.ToSidecar {
		var in Inbound
		same(line, &in)
		types[in.Type] = true
	}
	for _, line := range f.FromSidecar {
		var out Outbound
		same(line, &out)
		types[out.Type] = true
	}
	for _, typ := range []string{InSessionOpen, InSessionICE, InSessionClose, OutStarted, OutSessionAnswer, OutSessionState, OutSessionClosed} {
		if !types[typ] {
			t.Errorf("no %s line", typ)
		}
	}
}
