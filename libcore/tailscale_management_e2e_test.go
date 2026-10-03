package libcore

import (
	"encoding/json"
	"io"
	"net/netip"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/matsuridayo/libneko/neko_log"
)

// The private harness supplies an isolated node and two approved exit stable IDs.
// This gated test is separate from the credential-free unit/race suite.
func TestTailscaleManagementEndToEnd(t *testing.T) {
	path := os.Getenv("NEKOBOX_TAILSCALE_MANAGEMENT_E2E_CONFIG")
	if path == "" {
		t.Skip("NEKOBOX_TAILSCALE_MANAGEMENT_E2E_CONFIG is not set")
	}
	exitA := os.Getenv("NEKOBOX_TAILSCALE_E2E_EXIT_A_ID")
	exitB := os.Getenv("NEKOBOX_TAILSCALE_E2E_EXIT_B_ID")
	if exitA == "" || exitB == "" || exitA == exitB {
		t.Fatal("the private harness must supply two distinct exit stable IDs")
	}
	content, err := os.ReadFile(path)
	if err != nil {
		t.Fatal("cannot read private harness config")
	}
	if err := neko_log.SetupLog(1<<20, filepath.Join(t.TempDir(), "neko.log")); err != nil {
		t.Fatal("cannot initialize test logging")
	}
	var instance *BoxInstance
	t.Cleanup(func() {
		if instance != nil {
			instance.Close()
		}
	})
	start := func(config string) {
		t.Helper()
		instance, err = NewSingBoxInstance(config, nil)
		if err != nil {
			t.Fatal("cannot construct management node")
		}
		if instance.Start() != nil || TailscaleWaitReady(instance, "ts", false, 30000) != nil {
			t.Fatal("management node did not start and become ready")
		}
	}
	readStatus := func() tailscaleStatusJSON {
		t.Helper()
		raw, err := TailscaleStatus(instance, "ts")
		var status tailscaleStatusJSON
		if err != nil || json.Unmarshal([]byte(raw), &status) != nil {
			t.Fatal("management status unavailable")
		}
		if len(raw) > tailscaleMaxStatusBytes || len(status.Peers) > tailscaleMaxPeers || status.AuthURL != "" || status.NeedsLogin || status.NeedsApproval || status.Self == nil {
			t.Fatal("running status schema or sign-in fields invalid")
		}
		return status
	}
	waitExit := func(id string) {
		t.Helper()
		deadline := time.Now().Add(15 * time.Second)
		for {
			status := readStatus()
			if id == "" && status.CurrentExit == nil {
				return
			}
			if id != "" && status.CurrentExit != nil && status.CurrentExit.ID == id && status.CurrentExit.Live {
				return
			}
			if time.Now().After(deadline) {
				t.Fatal("exit preference/live state failed to converge")
			}
			time.Sleep(100 * time.Millisecond)
		}
	}
	apply := func(id string) *TailscaleExitNodeChange {
		t.Helper()
		change, err := SetTailscaleExitNode(instance, "ts", id)
		if err != nil {
			t.Fatal("exit change failed")
		}
		if id == "" {
			if change.SavedValue() != "" {
				t.Fatal("clear returned a nonempty saved value")
			}
		} else if ip, err := netip.ParseAddr(change.SavedValue()); err != nil || ip.String() != change.SavedValue() {
			t.Fatal("saved exit is not a canonical IP")
		}
		return change
	}
	commit := func(change *TailscaleExitNodeChange) {
		t.Helper()
		if change.Commit() != nil || change.Commit() != nil || change.Rollback() == nil {
			t.Fatal("commit finalization semantics")
		}
	}
	restart := func(saved string) {
		t.Helper()
		if instance.Close() != nil {
			t.Fatal("cannot close node for saved-config restart")
		}
		var config map[string]any
		if json.Unmarshal(content, &config) != nil {
			t.Fatal("private harness config must be JSON")
		}
		endpoints, ok := config["endpoints"].([]any)
		if !ok {
			t.Fatal("private harness config needs endpoints")
		}
		updated := false
		for _, endpoint := range endpoints {
			entry, ok := endpoint.(map[string]any)
			if ok && entry["tag"] == "ts" {
				entry["exit_node"] = saved
				updated = true
			}
		}
		if !updated {
			t.Fatal("private harness endpoint ts missing")
		}
		encoded, err := json.Marshal(config)
		if err != nil {
			t.Fatal("cannot encode restart config")
		}
		start(string(encoded))
	}

	start(string(content))
	commit(apply(exitA))
	waitExit(exitA)
	undo := apply(exitB)
	if _, err := SetTailscaleExitNode(instance, "ts", ""); err == nil {
		t.Fatal("concurrent unfinalized change accepted")
	}
	if undo.Rollback() != nil || undo.Rollback() != nil || undo.Commit() == nil {
		t.Fatal("rollback finalization semantics")
	}
	waitExit(exitA)
	change := apply(exitB)
	savedB := change.SavedValue()
	commit(change)
	waitExit(exitB)

	stream, err := StartTailscalePeerPing(instance, "ts", exitB, 10000)
	if err != nil {
		t.Fatal("cannot start peer diagnostic")
	}
	defer stream.Close()
	samples, successes := 0, 0
	for {
		raw, err := stream.Next(1000)
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal("private harness ping did not complete five samples")
		}
		if raw == "" {
			continue
		}
		var sample tailscalePingSample
		if json.Unmarshal([]byte(raw), &sample) != nil {
			t.Fatal("invalid diagnostic sample")
		}
		samples++
		if sample.PeerID != exitB || sample.PeerIP != savedB || sample.Sequence != samples {
			t.Fatal("diagnostic identity changed")
		}
		if sample.Error != "" {
			if sample.LatencyMs != nil || sample.Path != "unknown" {
				t.Fatal("error diagnostic appears successful")
			}
		} else {
			if sample.LatencyMs == nil {
				t.Fatal("successful diagnostic lacks latency")
			}
			successes++
		}
	}
	if samples != 5 || successes == 0 {
		t.Fatal("private harness must produce five diagnostics including a successful disco RTT")
	}
	stream.Close()
	restart(savedB)
	waitExit(exitB)
	commit(apply(""))
	waitExit("")
	restart("")
	waitExit("")
	observation, err := ObserveTailscaleStatus(instance, "ts")
	if err != nil {
		t.Fatal("cannot open shutdown observation")
	}
	if instance.Close() != nil {
		t.Fatal("cannot close observed node")
	}
	if _, err := observation.Next(100); err == nil {
		t.Fatal("closed box observation still admitted reads")
	}
	observation.Close()
}
