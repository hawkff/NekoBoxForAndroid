package main

import (
	"os"
	"syscall"
	"testing"
	"time"
)

func TestWaitAfterReadySignalIsGraceful(t *testing.T) {
	signals := make(chan os.Signal, 1)
	signals <- syscall.SIGTERM

	if !waitAfterReady(signals, make(chan time.Time), func() bool { return true }) {
		t.Fatal("signal shutdown was reported as runtime failure")
	}
}

func TestWaitAfterReadyStopsOnFirstUnhealthyTick(t *testing.T) {
	ticks := make(chan time.Time, 1)
	ticks <- time.Now()

	if waitAfterReady(make(chan os.Signal), ticks, func() bool { return false }) {
		t.Fatal("stopped runtime was reported as graceful shutdown")
	}
}

func TestWaitAfterReadyHealthyTicksContinue(t *testing.T) {
	signals := make(chan os.Signal)
	ticks := make(chan time.Time)
	result := make(chan bool, 1)
	checks := 0
	go func() {
		result <- waitAfterReady(signals, ticks, func() bool {
			checks++
			return true
		})
	}()

	ticks <- time.Now()
	signals <- syscall.SIGTERM
	if graceful := <-result; !graceful {
		t.Fatal("healthy runtime tick stopped the wait loop")
	}
	if checks != 1 {
		t.Fatalf("running checks = %d, want 1", checks)
	}
}
