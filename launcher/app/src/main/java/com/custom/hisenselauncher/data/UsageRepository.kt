package com.custom.hisenselauncher.data

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** App-level history from Android usage stats (granted over adb: appops ... GET_USAGE_STATS allow). */
class UsageRepository(private val ctx: Context) {

    fun hasAccess(): Boolean {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        else @Suppress("DEPRECATION") ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** Packages used in the last [days] days, most recent first (ignores sub-minute blips). */
    fun recentPackages(days: Int = 21): List<String> {
        if (!hasAccess()) return emptyList()
        val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val stats = runCatching { usm.queryAndAggregateUsageStats(end - days * 86_400_000L, end) }.getOrNull() ?: return emptyList()
        return stats.values
            .filter { it.totalTimeInForeground > 60_000 && it.packageName != ctx.packageName }
            .sortedByDescending { it.lastTimeUsed }
            .map { it.packageName }
    }
}
