package com.centrifugamais.app

import android.content.Context

/**
 * What the app has learned about which programs this machine has:
 * seen on the panel/status, started successfully from the app, or refused.
 */
object Programs {
    private fun sp(ctx: Context) = ctx.getSharedPreferences("programs", Context.MODE_PRIVATE)

    private fun get(ctx: Context, key: String): Set<Int> =
        (sp(ctx).getStringSet(key, emptySet()) ?: emptySet()).mapNotNull { it.toIntOrNull() }.toSet()

    private fun add(ctx: Context, key: String, code: Int) {
        val cur = get(ctx, key)
        if (code in cur) return
        sp(ctx).edit().putStringSet(key, (cur + code).map { it.toString() }.toSet()).apply()
    }

    private fun remove(ctx: Context, key: String, code: Int) {
        val cur = get(ctx, key)
        if (code !in cur) return
        sp(ctx).edit().putStringSet(key, (cur - code).map { it.toString() }.toSet()).apply()
    }

    fun seen(ctx: Context) = get(ctx, "seen")
    fun worked(ctx: Context) = get(ctx, "worked")
    fun failed(ctx: Context) = get(ctx, "failed")

    fun markSeen(ctx: Context, code: Int) {
        add(ctx, "seen", code)
        remove(ctx, "failed", code)
    }

    fun markWorked(ctx: Context, code: Int) {
        add(ctx, "worked", code)
        remove(ctx, "failed", code)
    }

    fun markFailed(ctx: Context, code: Int) {
        if (code !in worked(ctx) && code !in seen(ctx)) add(ctx, "failed", code)
    }
}
