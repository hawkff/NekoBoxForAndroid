package io.nekohasekai.sagernet.aidl;

import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback;

interface ISagerNetService {
  int getState();
  String getProfileName();

  void registerCallback(in ISagerNetServiceCallback cb, int id);
  oneway void unregisterCallback(in ISagerNetServiceCallback cb);

  int urlTest();
  // Connection test through the running outbound of one profile in the current
  // configuration; fails when the profile is not part of it.
  int urlTestProfile(long profileId);
  // Peers of a running Tailscale profile as JSON (name, dnsName, ips, online, exitNode).
  String tailscalePeers(long profileId);

  // Live routed connections as JSON (ConnectionEntry array); closed ones too when the
  // bounded history is enabled through connection diagnostics.
  String connections(boolean includeClosed);
}
