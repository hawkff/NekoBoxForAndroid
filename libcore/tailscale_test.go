package libcore

import (
	"testing"

	"github.com/sagernet/tailscale/ipn"
	"github.com/sagernet/tailscale/ipn/ipnstate"
	"github.com/sagernet/tailscale/types/key"
)

func TestTailscaleReady(t *testing.T) {
	runningWithPeer := func(peer *ipnstate.PeerStatus) *ipnstate.Status {
		return &ipnstate.Status{
			BackendState: ipn.Running.String(),
			Peer:         map[key.NodePublic]*ipnstate.PeerStatus{{}: peer},
		}
	}
	tests := []struct {
		name           string
		status         *ipnstate.Status
		exitNodeWanted bool
		want           bool
	}{
		{
			name:   "nil status without exit",
			status: nil,
		},
		{
			name:           "nil status with exit",
			status:         nil,
			exitNodeWanted: true,
		},
		{
			name:   "running without requested exit",
			status: &ipnstate.Status{BackendState: ipn.Running.String()},
			want:   true,
		},
		{
			name: "selected approved live peer without snapshot",
			status: runningWithPeer(&ipnstate.PeerStatus{
				ExitNode: true, ExitNodeOption: true, Online: true,
			}),
			exitNodeWanted: true,
			want:           true,
		},
		{
			name: "selection does not require control plane online flag",
			status: runningWithPeer(&ipnstate.PeerStatus{
				ExitNode: true, ExitNodeOption: true, Online: false,
			}),
			exitNodeWanted: true,
			want:           true,
		},
		{
			name: "stale snapshot without live peers",
			status: &ipnstate.Status{
				BackendState:   ipn.Running.String(),
				ExitNodeStatus: &ipnstate.ExitNodeStatus{ID: "removed-exit", Online: true},
			},
			exitNodeWanted: true,
		},
		{
			name: "stale snapshot with unselected live peer",
			status: &ipnstate.Status{
				BackendState:   ipn.Running.String(),
				ExitNodeStatus: &ipnstate.ExitNodeStatus{ID: "old-exit", Online: true},
				Peer: map[key.NodePublic]*ipnstate.PeerStatus{
					{}: {ID: "old-exit", ExitNodeOption: true, Online: true},
				},
			},
			exitNodeWanted: true,
		},
		{
			name:           "nil peer map",
			status:         &ipnstate.Status{BackendState: ipn.Running.String()},
			exitNodeWanted: true,
		},
		{
			name:           "nil peer entry",
			status:         runningWithPeer(nil),
			exitNodeWanted: true,
		},
		{
			name:           "zero value peer",
			status:         runningWithPeer(&ipnstate.PeerStatus{}),
			exitNodeWanted: true,
		},
		{
			name: "selected but not eligible",
			status: runningWithPeer(&ipnstate.PeerStatus{
				ExitNode: true, Online: true,
			}),
			exitNodeWanted: true,
		},
		{
			name: "eligible but not selected",
			status: runningWithPeer(&ipnstate.PeerStatus{
				ExitNodeOption: true, Online: true,
			}),
			exitNodeWanted: true,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := tailscaleReady(test.status, test.exitNodeWanted); got != test.want {
				t.Fatalf("tailscaleReady(status, %v) = %v, want %v", test.exitNodeWanted, got, test.want)
			}
		})
	}
}

func TestTailscaleReadyRequiresRunning(t *testing.T) {
	for _, state := range []string{"", ipn.NoState.String(), ipn.NeedsLogin.String(), ipn.NeedsMachineAuth.String(), ipn.Stopped.String(), ipn.Starting.String()} {
		t.Run(state, func(t *testing.T) {
			status := &ipnstate.Status{
				BackendState:   state,
				ExitNodeStatus: &ipnstate.ExitNodeStatus{ID: "exit", Online: true},
				Peer: map[key.NodePublic]*ipnstate.PeerStatus{
					{}: {ID: "exit", ExitNode: true, ExitNodeOption: true, Online: true},
				},
			}
			for _, exitNodeWanted := range []bool{false, true} {
				if tailscaleReady(status, exitNodeWanted) {
					t.Fatalf("tailscaleReady(status, %v) = true for backend state %q", exitNodeWanted, state)
				}
			}
		})
	}
}
