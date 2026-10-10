package libcore

import (
	"io"
	"log"
	"os"
	"sync"

	"golang.org/x/sys/unix"
)

// nekoLog is cache/neko.log. The UI and :bg processes of the app append to it concurrently,
// so the startup trim, every write and NekoLogClear run under lockLog. Until setupLog runs,
// and when opening the file fails, messages go to stderr instead.
var (
	nekoLog        *os.File
	nekoLogAccess  sync.Mutex
	nekoLogDisable bool
)

// lockLog serializes this process's log file operations and takes the cross-process flock
// around them; call the result to release both. flock does not exclude goroutines that share
// one open file description, so the mutex is what keeps them apart.
func lockLog(f *os.File) func() {
	nekoLogAccess.Lock()
	fd := int(f.Fd())
	unix.Flock(fd, unix.LOCK_EX)
	return func() {
		unix.Flock(fd, unix.LOCK_UN)
		nekoLogAccess.Unlock()
	}
}

// setupLog opens path for append, keeps only its last maxSize bytes when trim is set, and
// routes the standard logger and stderr (Go runtime panics) into it.
func setupLog(maxSize int, path string, trim bool) error {
	if nekoLog != nil {
		return nil
	}
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		return err
	}
	if trim {
		keepLogTail(f, maxSize)
	}
	unix.Dup2(int(f.Fd()), int(os.Stderr.Fd()))
	nekoLog = f
	log.SetFlags(log.LstdFlags | log.LUTC)
	log.SetOutput(nekoLogWriter{})
	return nil
}

// keepLogTail drops everything but the last maxSize bytes of f.
func keepLogTail(f *os.File, maxSize int) {
	defer lockLog(f)()
	size, err := f.Seek(0, io.SeekEnd)
	if err != nil || size <= int64(maxSize) {
		return
	}
	if _, err := f.Seek(-int64(maxSize), io.SeekEnd); err != nil {
		return
	}
	tail, err := io.ReadAll(f)
	if err != nil || f.Truncate(0) != nil {
		return
	}
	f.Write(tail)
}

type nekoLogWriter struct{}

func (nekoLogWriter) Write(p []byte) (int, error) {
	if nekoLogDisable {
		return len(p), nil
	}
	if nekoLog == nil {
		return os.Stderr.Write(p)
	}
	defer lockLog(nekoLog)()
	return nekoLog.Write(p)
}
