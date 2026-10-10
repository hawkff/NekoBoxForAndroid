package libcore

import (
	"io"
	"log"
	"os"

	"golang.org/x/sys/unix"
)

// nekoLog is cache/neko.log. The UI and :bg processes of the app append to it concurrently,
// so the startup trim and every write hold an exclusive flock. Until setupLog runs, and when
// opening the file fails, messages go to stderr instead.
var (
	nekoLog        *os.File
	nekoLogDisable bool
)

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
	fd := int(f.Fd())
	unix.Flock(fd, unix.LOCK_EX)
	defer unix.Flock(fd, unix.LOCK_UN)
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
	fd := int(nekoLog.Fd())
	unix.Flock(fd, unix.LOCK_EX)
	defer unix.Flock(fd, unix.LOCK_UN)
	return nekoLog.Write(p)
}
