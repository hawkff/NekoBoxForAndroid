package libcore

import (
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"
)

func TestUrlTestRequestsTwiceWithoutFollowingRedirects(t *testing.T) {
	var hits atomic.Int32
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		// Following this would fail: nothing listens on port 1.
		http.Redirect(w, r, "http://127.0.0.1:1/", http.StatusFound)
	}))
	defer server.Close()

	latency, err := urlTest(&http.Client{Transport: &http.Transport{}}, server.URL, 5000)
	if err != nil {
		t.Fatal(err)
	}
	if hits.Load() != 2 {
		t.Fatalf("expected 2 requests, got %d", hits.Load())
	}
	if latency < 0 {
		t.Fatalf("negative latency %d", latency)
	}
	if _, err := urlTest(&http.Client{Transport: &http.Transport{}}, "http://127.0.0.1:1/", 500); err == nil {
		t.Fatal("expected an error for a closed port")
	}
}
