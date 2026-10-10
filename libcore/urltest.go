package libcore

import (
	"context"
	"net/http"
	"net/http/httptrace"
	"time"
)

// urlTest fetches link twice over client and returns the second request's time from headers
// written to first response byte, so name lookup and the TCP/TLS handshakes stay out of it.
// Redirects count as a reply and are not followed.
func urlTest(client *http.Client, link string, timeout int32) (int32, error) {
	defer client.CloseIdleConnections()
	client.CheckRedirect = func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeout)*time.Millisecond)
	defer cancel()
	var wroteHeaders, firstByte time.Time
	ctx = httptrace.WithClientTrace(ctx, &httptrace.ClientTrace{
		WroteHeaders:         func() { wroteHeaders = time.Now() },
		GotFirstResponseByte: func() { firstByte = time.Now() },
	})
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, link, nil)
	if err != nil {
		return 0, err
	}
	for range 2 {
		response, err := client.Do(request)
		if err != nil {
			return 0, err
		}
		response.Body.Close()
	}
	return int32(firstByte.Sub(wroteHeaders).Milliseconds()), nil
}
