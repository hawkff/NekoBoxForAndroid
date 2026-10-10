package libcore

import (
	"net"
	"syscall"
	"testing"
	"time"

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

func TestServeProtectRejectsMessagesCarryingSeveralFds(t *testing.T) {
	t.Chdir(t.TempDir())
	protected := make(chan int, 1)
	closer := serveProtect("protect_path", func(fd int) { protected <- fd })
	if closer == nil {
		t.Fatal("protect server did not start")
	}
	defer closer.Close()

	conn, err := net.DialUnix("unix", nil, &net.UnixAddr{Name: "protect_path", Net: "unix"})
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	var sockets [2]int
	for i := range sockets {
		if sockets[i], err = unix.Socket(unix.AF_UNIX, unix.SOCK_DGRAM, 0); err != nil {
			t.Fatal(err)
		}
		defer unix.Close(sockets[i])
	}
	if _, _, err := conn.WriteMsgUnix([]byte{1}, syscall.UnixRights(sockets[0], sockets[1]), nil); err != nil {
		t.Fatal(err)
	}
	conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	if n, err := conn.Read(make([]byte, 1)); err == nil || n != 0 {
		t.Fatal("server answered a message carrying two fds")
	}
	select {
	case fd := <-protected:
		t.Fatalf("protect received fd %d from a rejected message", fd)
	default:
	}
}
