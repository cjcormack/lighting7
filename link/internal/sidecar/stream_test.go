package sidecar

import (
	"crypto/sha256"
	"encoding/json"
	"io"
	"net/http"
	"runtime"
	"strconv"
	"sync/atomic"
	"testing"
	"time"

	"github.com/pion/webrtc/v4"
)

const streamBytes = 100 << 20

// pattern is a deterministic stream of n bytes.
type pattern struct{ left, i int }

func (p *pattern) Read(b []byte) (int, error) {
	if p.left == 0 {
		return 0, io.EOF
	}
	n := min(len(b), p.left)
	for k := 0; k < n; k++ {
		b[k] = byte(p.i * 31)
		p.i++
	}
	p.left -= n
	return n, nil
}

func patternSum(n int) [32]byte {
	h := sha256.New()
	_, _ = io.Copy(h, &pattern{left: n})
	var out [32]byte
	copy(out[:], h.Sum(nil))
	return out
}

// peakHeap samples the heap in use until stop, answering the most it saw above where it started.
func peakHeap(stop <-chan struct{}) <-chan uint64 {
	out := make(chan uint64, 1)
	runtime.GC()
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	base := m.HeapInuse
	go func() {
		var peak uint64
		tick := time.NewTicker(20 * time.Millisecond)
		defer tick.Stop()
		for {
			select {
			case <-stop:
				out <- peak
				return
			case <-tick.C:
				runtime.ReadMemStats(&m)
				if m.HeapInuse > base && m.HeapInuse-base > peak {
					peak = m.HeapInuse - base
				}
			}
		}
	}()
	return out
}

// The bound covers both peers in this process, the desk and the test's own buffers: a fraction of
// the 100 MB, which would show here if any side buffered the body.
const heapBound = 48 << 20

func TestA100MBUploadStreamsInBoundedMemory(t *testing.T) {
	if testing.Short() {
		t.Skip("streams 100 MB")
	}
	desk := newFakeDesk()
	got := make(chan [32]byte, 1)
	desk.mux.HandleFunc("/upload", func(w http.ResponseWriter, r *http.Request) {
		h := sha256.New()
		n, _ := io.Copy(h, r.Body)
		var sum [32]byte
		copy(sum[:], h.Sum(nil))
		got <- sum
		_, _ = w.Write([]byte(strconv.FormatInt(n, 10)))
	})
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	stop := make(chan struct{})
	peak := peakHeap(stop)
	started := time.Now()

	c := b.open(LabelHTTP)
	c.sendJSON(t, KindHead, RequestHead{Method: "POST", Path: "/upload", Headers: []Header{{"content-type", "application/pdf"}, {"content-length", strconv.Itoa(streamBytes)}}})
	src := &pattern{left: streamBytes}
	buf := make([]byte, MaxChunkBytes)
	for {
		n, err := src.Read(buf)
		if n > 0 {
			c.send(t, EncodeFrame(KindData, buf[:n]))
		}
		if err == io.EOF {
			break
		}
	}
	c.send(t, []byte{KindEnd})
	head, body := readResponse(t, c)
	close(stop)
	if head.Status != 200 || string(body) != strconv.Itoa(streamBytes) {
		t.Fatalf("answered %d %q", head.Status, body)
	}
	if sum := <-got; sum != patternSum(streamBytes) {
		t.Fatal("the desk read other bytes than were sent")
	}
	p := <-peak
	t.Logf("100 MB up in %v, heap peak +%d MiB", time.Since(started).Round(time.Millisecond), p>>20)
	if p > heapBound {
		t.Fatalf("the heap grew by %d MiB", p>>20)
	}
}

func TestA100MBDownloadStreamsInBoundedMemory(t *testing.T) {
	if testing.Short() {
		t.Skip("streams 100 MB")
	}
	desk := newFakeDesk()
	desk.mux.HandleFunc("/download", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", strconv.Itoa(streamBytes))
		_, _ = io.Copy(w, &pattern{left: streamBytes})
	})
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	stop := make(chan struct{})
	peak := peakHeap(stop)
	started := time.Now()

	c := b.open(LabelHTTP)
	hash := sha256.New()
	var total atomic.Int64
	done := make(chan Frame, 2)
	var head ResponseHead
	sink := func(m webrtc.DataChannelMessage) {
		f, err := DecodeFrame(m.Data)
		if err != nil {
			t.Errorf("a bad frame: %v", err)
			return
		}
		switch f.Kind {
		case KindHead:
			_ = json.Unmarshal(f.Payload, &head)
		case KindData:
			hash.Write(f.Payload)
			total.Add(int64(len(f.Payload)))
		default:
			done <- f
		}
	}
	c.sink.Store(&sink)
	c.sendJSON(t, KindHead, RequestHead{Method: "GET", Path: "/download", Headers: []Header{}})
	c.send(t, []byte{KindEnd})
	select {
	case f := <-done:
		if f.Kind != KindEnd {
			t.Fatalf("ended with %#x %q", f.Kind, f.Payload)
		}
	case <-time.After(5 * time.Minute):
		t.Fatalf("only %d bytes arrived", total.Load())
	}
	close(stop)
	var sum [32]byte
	copy(sum[:], hash.Sum(nil))
	if head.Status != 200 || total.Load() != streamBytes || sum != patternSum(streamBytes) {
		t.Fatalf("status %d, %d bytes", head.Status, total.Load())
	}
	p := <-peak
	t.Logf("100 MB down in %v, heap peak +%d MiB", time.Since(started).Round(time.Millisecond), p>>20)
	if p > heapBound {
		t.Fatalf("the heap grew by %d MiB", p>>20)
	}
}

// A browser that stops reading holds the sidecar at its high-water mark, and the desk's writes stop.
func TestAPeerThatStopsReadingHoldsTheDeskBack(t *testing.T) {
	desk := newFakeDesk()
	var written atomic.Int64
	deskDone := make(chan struct{})
	desk.mux.HandleFunc("/forever", func(w http.ResponseWriter, r *http.Request) {
		defer close(deskDone)
		chunk := make([]byte, 32*1024)
		for {
			n, err := w.Write(chunk)
			written.Add(int64(n))
			if err != nil {
				return
			}
			w.(http.Flusher).Flush()
		}
	})
	h := newHarness(t, desk, nil)
	b := h.connect("s1")
	b.ready()
	c := b.open(LabelHTTP)
	gate := make(chan struct{})
	c.gate.Store(&gate)
	c.sendJSON(t, KindHead, RequestHead{Method: "GET", Path: "/forever", Headers: []Header{}})
	c.send(t, []byte{KindEnd})

	time.Sleep(2 * time.Second)
	first := written.Load()
	time.Sleep(2 * time.Second)
	second := written.Load()
	t.Logf("the desk wrote %d KiB, then %d KiB more", first>>10, (second-first)>>10)
	if second-first > 1<<20 {
		t.Fatalf("the desk kept writing: %d bytes in 2 s", second-first)
	}
	if second > 32<<20 {
		t.Fatalf("the desk wrote %d MiB into a peer that reads nothing", second>>20)
	}
	if sess := h.sc.find("s1"); sess == nil {
		t.Fatal("the session went")
	}

	// Ending the session frees the desk's handler.
	h.send(Inbound{Type: InSessionClose, SID: "s1"})
	close(gate)
	select {
	case <-deskDone:
	case <-time.After(15 * time.Second):
		t.Fatal("the desk's response was never abandoned")
	}
}
