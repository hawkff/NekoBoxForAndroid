package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface TailscaleExitChange {
    fun savedValue(): String
    fun commit()
    fun rollback()
}

internal data class TailscaleExitResult(val outcome: String, val savedExit: String, val errorCode: String = "")

/** Native methods have their own deadlines; cancellation must never detach a JNI finalizer. */
internal suspend fun finalizeTailscaleExit(
    oldExit: String,
    begin: () -> TailscaleExitChange,
    save: (String) -> Unit,
    applied: (Boolean) -> Unit,
    rolledBack: () -> Unit,
): TailscaleExitResult = withContext(NonCancellable) {
    withTimeout(30_000) {
        val change = try {
            begin()
        } catch (_: Exception) {
            return@withTimeout TailscaleExitResult("failed-unchanged", oldExit, "tailscale:apply-failed")
        }
        val saved = change.savedValue()
        applied(saved.isNotEmpty())
        try {
            save(saved)
        } catch (e: Exception) {
            return@withTimeout try {
                change.rollback()
                rolledBack()
                TailscaleExitResult(
                    if (e.message == "tailscale:conflict") "conflict" else "failed-rolled-back",
                    oldExit,
                    if (e.message == "tailscale:conflict") "tailscale:conflict" else "tailscale:save-failed",
                )
            } catch (_: Exception) {
                TailscaleExitResult("diverged", oldExit, "tailscale:rollback-failed")
            }
        }
        try {
            change.commit()
            TailscaleExitResult("applied-and-saved", saved)
        } catch (_: Exception) {
            // The DB is authoritative after this point. Never undo a durable or newer choice.
            TailscaleExitResult("diverged", saved, "tailscale:commit-failed")
        }
    }
}
