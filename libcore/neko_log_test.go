package libcore

import (
	"os"
	"path/filepath"
	"testing"
)

func TestKeepLogTailKeepsOnlyTheNewestBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "neko.log")
	f, err := os.OpenFile(path, os.O_RDWR|os.O_APPEND|os.O_CREATE, 0o644)
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	f.WriteString("0123456789")

	keepLogTail(f, 4)
	if content, _ := os.ReadFile(path); string(content) != "6789" {
		t.Fatalf("kept %q", content)
	}
	keepLogTail(f, 4)
	if content, _ := os.ReadFile(path); string(content) != "6789" {
		t.Fatalf("a file within the limit changed to %q", content)
	}
}
