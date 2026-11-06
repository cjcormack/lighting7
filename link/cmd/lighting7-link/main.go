// lighting7-link is the desk's WebRTC sidecar. The desk starts it and is its only client: the link
// secret as the first line on stdin, then JSON lines each way (docs/cloud-engineering.md §"The
// sidecar"). It stops when stdin ends.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/signal"
	"syscall"

	"github.com/cjcormack/lighting7/link/internal/sidecar"
)

func main() {
	target := flag.String("target", "", "the desk's public listener, http://host:port")
	build := flag.String("build", "", "the desk's build")
	maxSessions := flag.Int("max-sessions", sidecar.DefaultMaxSessions, "the most sessions open at once")
	flag.Parse()

	logger := log.New(os.Stderr, "lighting7-link: ", log.LstdFlags)
	u, err := url.Parse(*target)
	if err != nil || u.Scheme != "http" || u.Host == "" || (u.Path != "" && u.Path != "/") {
		logger.Fatalf("-target must be http://host:port, not %q", *target)
	}
	u.Path = ""

	// stdout is the desk's protocol: anything else that prints goes to stderr.
	protocol := os.Stdout
	os.Stdout = os.Stderr

	lines := sidecar.NewLineReader(os.Stdin, sidecar.MaxLineBytes)
	secret, err := sidecar.ReadSecret(lines)
	if err != nil {
		logger.Fatal(err)
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	s := sidecar.New(sidecar.Config{Target: u, Build: *build, Secret: secret, MaxSessions: *maxSessions, Log: logger}, protocol)
	if err := s.Run(ctx, lines); err != nil {
		fmt.Fprintln(os.Stderr, "lighting7-link:", err)
		os.Exit(1)
	}
}
