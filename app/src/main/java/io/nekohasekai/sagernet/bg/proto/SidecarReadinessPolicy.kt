package io.nekohasekai.sagernet.bg.proto

internal fun readinessMarkerSatisfied(markerRequired: Boolean, markerPresent: Boolean) =
    !markerRequired || markerPresent

internal fun shouldFailSidecarReadiness(pendingPorts: Set<Int>, requiredPorts: Set<Int>, strict: Boolean) =
    strict || pendingPorts.any { it in requiredPorts }
