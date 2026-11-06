package sidecar

import (
	"encoding/json"
	"errors"
	"fmt"
	"strings"
)

// The link framing (docs/cloud-protocol.md §"The link framing", cloud-protocol's LinkFraming): every
// message on an http or ws channel is binary, its first byte its kind.
const (
	LabelCtl  = "ctl"
	LabelHTTP = "http"
	LabelWS   = "ws"

	KindHead       byte = 0x01
	KindData       byte = 0x02
	KindEnd        byte = 0x03
	KindReset      byte = 0x04
	KindTextPart   byte = 0x10
	KindTextEnd    byte = 0x11
	KindBinaryPart byte = 0x12
	KindBinaryEnd  byte = 0x13
	KindClose      byte = 0x20

	MaxChunkBytes             = 16 * 1024
	MaxHeadBytes              = 64*1024 - 1
	BufferedHighBytes         = 1024 * 1024
	BufferedLowThresholdBytes = 256 * 1024
)

// StrippedHeaders are removed from a head's headers, compared without case, before the sidecar sets
// its own Authorization, Lighting7-Link and Origin.
var StrippedHeaders = []string{
	"authorization",
	"cookie",
	"host",
	"lighting7-link",
	"origin",
	"connection",
	"keep-alive",
	"proxy-authorization",
	"proxy-connection",
	"te",
	"trailer",
	"transfer-encoding",
	"upgrade",
	"sec-websocket-key",
	"sec-websocket-version",
	"sec-websocket-extensions",
}

// Kinds names every kind, as framing.json does.
var Kinds = map[string]byte{
	"HEAD":        KindHead,
	"DATA":        KindData,
	"END":         KindEnd,
	"RESET":       KindReset,
	"TEXT_PART":   KindTextPart,
	"TEXT_END":    KindTextEnd,
	"BINARY_PART": KindBinaryPart,
	"BINARY_END":  KindBinaryEnd,
	"CLOSE":       KindClose,
}

// Header is one header as the framing carries it, [name, value].
type Header [2]string

// RequestHead is the browser's HEAD: an HTTP request's, or a WebSocket's without a method.
type RequestHead struct {
	Method  string   `json:"method,omitempty"`
	Path    string   `json:"path"`
	Headers []Header `json:"headers"`
	Origin  string   `json:"origin,omitempty"`
}

// ResponseHead is the sidecar's HEAD.
type ResponseHead struct {
	Status  int      `json:"status"`
	Headers []Header `json:"headers"`
}

// Reset abandons an exchange.
type Reset struct {
	Reason string `json:"reason"`
}

// Close ends a WebSocket.
type Close struct {
	Code   int    `json:"code"`
	Reason string `json:"reason"`
}

// Frame is one decoded data-channel message.
type Frame struct {
	Kind    byte
	Payload []byte
}

var errEmptyFrame = errors.New("an empty frame")

// DecodeFrame splits a message into its kind and payload, checking the kind's size rules.
func DecodeFrame(message []byte) (Frame, error) {
	if len(message) == 0 {
		return Frame{}, errEmptyFrame
	}
	f := Frame{Kind: message[0], Payload: message[1:]}
	n := len(f.Payload)
	switch f.Kind {
	case KindHead, KindReset, KindClose:
		if n > MaxHeadBytes {
			return f, fmt.Errorf("a frame of kind %#x carries %d bytes, over %d", f.Kind, n, MaxHeadBytes)
		}
	case KindEnd:
		if n != 0 {
			return f, errors.New("an END frame carries no payload")
		}
	case KindData, KindTextPart, KindBinaryPart:
		if n < 1 || n > MaxChunkBytes {
			return f, fmt.Errorf("a frame of kind %#x carries %d bytes, outside 1 to %d", f.Kind, n, MaxChunkBytes)
		}
	case KindTextEnd, KindBinaryEnd:
		if n > MaxChunkBytes {
			return f, fmt.Errorf("a frame of kind %#x carries %d bytes, over %d", f.Kind, n, MaxChunkBytes)
		}
	default:
		return f, fmt.Errorf("an unknown frame kind %#x", f.Kind)
	}
	return f, nil
}

// EncodeFrame prefixes payload with kind.
func EncodeFrame(kind byte, payload []byte) []byte {
	out := make([]byte, 1+len(payload))
	out[0] = kind
	copy(out[1:], payload)
	return out
}

// EncodeJSONFrame encodes v as kind's JSON payload, refusing one over MaxHeadBytes.
func EncodeJSONFrame(kind byte, v any) ([]byte, error) {
	payload, err := json.Marshal(v)
	if err != nil {
		return nil, err
	}
	if len(payload) > MaxHeadBytes {
		return nil, fmt.Errorf("a frame's JSON of %d bytes is over %d", len(payload), MaxHeadBytes)
	}
	return EncodeFrame(kind, payload), nil
}

// Stripped reports whether name is one of StrippedHeaders.
func Stripped(name string) bool { return contains(StrippedHeaders, name) }

// contains reports whether list, of lower-case names, holds name in any case.
func contains(list []string, name string) bool {
	lower := strings.ToLower(name)
	for _, h := range list {
		if h == lower {
			return true
		}
	}
	return false
}
