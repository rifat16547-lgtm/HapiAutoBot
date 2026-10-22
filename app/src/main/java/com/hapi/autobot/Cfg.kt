package com.hapi.autobot

import android.content.Context

object Cfg {
    val APPS = mapOf("hapi" to "com.mejoy.hapi", "yari" to "com.funny.yari")

    fun key(ctx: Context): String =
        ctx.getSharedPreferences("hapi", Context.MODE_PRIVATE).getString("app", "hapi") ?: "hapi"

    fun setKey(ctx: Context, k: String) {
        ctx.getSharedPreferences("hapi", Context.MODE_PRIVATE).edit().putString("app", k).apply()
    }

    fun pkg(ctx: Context): String = APPS[key(ctx)] ?: APPS.getValue("hapi")
}
