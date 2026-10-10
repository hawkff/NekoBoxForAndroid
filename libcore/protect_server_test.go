package libcore

import (
	"testing"

	"golang.org/x/sys/unix"
)

func TestServeProtectHandsTheClientSocketToProtect(t *testing.T) {
	t.Chdir(t.TempDir())
	received := make(chan unix.Stat_t, 1)
	closer := serveProtect("protect_path", func(fd int) {
		var stat unix.Stat_t
		if err := unix.Fstat(fd, &stat); err != nil {
			t.Error(err)
		}
		received <- stat
	})
	if closer == nil {
		t.Fatal("protect server did not start")
	}
	defer closer.Close()

	socket, err := unix.Socket(unix.AF_UNIX, unix.SOCK_DGRAM, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer unix.Close(socket)
	if err := sendFdToProtect(socket, "protect_path"); err != nil {
		t.Fatal(err)
	}
	var sent unix.Stat_t
	if err := unix.Fstat(socket, &sent); err != nil {
		t.Fatal(err)
	}
	if got := <-received; got.Ino != sent.Ino || got.Dev != sent.Dev {
		t.Fatalf("protected inode %d, sent %d", got.Ino, sent.Ino)
	}
}
