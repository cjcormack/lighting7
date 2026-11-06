package sidecar

import (
	"context"
	"errors"
	"io"
	"time"

	"github.com/pion/webrtc/v4"
)

// inboundQueue is how many messages a channel holds before its reader blocks, which holds the
// browser back through SCTP's flow control: at most 16 full chunks, 256 KiB, per channel.
const inboundQueue = 16

var errChannelClosed = errors.New("the data channel closed")

// channel is one data channel with a bounded inbound queue and backpressured sends.
type channel struct {
	dc     *webrtc.DataChannel
	ctx    context.Context
	cancel context.CancelFunc
	in     chan webrtc.DataChannelMessage
	low    chan struct{}
}

func newChannel(parent context.Context, dc *webrtc.DataChannel) *channel {
	ctx, cancel := context.WithCancel(parent)
	c := &channel{
		dc:     dc,
		ctx:    ctx,
		cancel: cancel,
		in:     make(chan webrtc.DataChannelMessage, inboundQueue),
		low:    make(chan struct{}, 1),
	}
	dc.SetBufferedAmountLowThreshold(BufferedLowThresholdBytes)
	dc.OnBufferedAmountLow(func() {
		select {
		case c.low <- struct{}{}:
		default:
		}
	})
	dc.OnMessage(func(m webrtc.DataChannelMessage) {
		select {
		case c.in <- m:
		case <-ctx.Done():
		}
	})
	dc.OnClose(cancel)
	return c
}

// next is the next message, or an error once the channel or its session has closed.
func (c *channel) next() (webrtc.DataChannelMessage, error) { return c.nextOr(nil) }

// nextOr is next, or an error once stop closes.
func (c *channel) nextOr(stop <-chan struct{}) (webrtc.DataChannelMessage, error) {
	select {
	case m := <-c.in:
		return m, nil
	case <-stop:
		return webrtc.DataChannelMessage{}, errChannelClosed
	case <-c.ctx.Done():
		// A message queued before the close still counts.
		select {
		case m := <-c.in:
			return m, nil
		default:
			return webrtc.DataChannelMessage{}, errChannelClosed
		}
	}
}

// send waits while the channel holds more than BufferedHighBytes, then sends message.
func (c *channel) send(message []byte) error {
	for c.dc.BufferedAmount() > BufferedHighBytes {
		select {
		case <-c.low:
		case <-c.ctx.Done():
			return errChannelClosed
		case <-time.After(100 * time.Millisecond):
		}
	}
	if c.ctx.Err() != nil {
		return errChannelClosed
	}
	return c.dc.Send(message)
}

func (c *channel) sendText(text string) error {
	if c.ctx.Err() != nil {
		return errChannelClosed
	}
	return c.dc.SendText(text)
}

func (c *channel) sendJSON(kind byte, v any) error {
	frame, err := EncodeJSONFrame(kind, v)
	if err != nil {
		return err
	}
	return c.send(frame)
}

// close closes the channel once what it has buffered is sent, or after drainWait.
func (c *channel) close() {
	c.dc.SetBufferedAmountLowThreshold(0)
	deadline := time.NewTimer(drainWait)
	defer deadline.Stop()
drain:
	for c.ctx.Err() == nil && c.dc.BufferedAmount() > 0 {
		select {
		case <-c.low:
		case <-c.ctx.Done():
			break drain
		case <-deadline.C:
			break drain
		}
	}
	_ = c.dc.Close()
	c.cancel()
}

// fill reads r into buf until buf is full or r ends. Only an unwrapped io.EOF is an end: a body
// or a message cut short reports another error, which the caller must not take for one.
func fill(r io.Reader, buf []byte) (n int, ended bool, err error) {
	for n < len(buf) {
		k, err := r.Read(buf[n:])
		n += k
		if err == io.EOF {
			return n, true, nil
		}
		if err != nil {
			return n, false, err
		}
	}
	return n, false, nil
}

const drainWait = 30 * time.Second

// after closes its channel once d has passed.
func after(d time.Duration) <-chan struct{} {
	done := make(chan struct{})
	time.AfterFunc(d, func() { close(done) })
	return done
}
