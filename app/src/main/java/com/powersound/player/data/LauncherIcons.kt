package com.powersound.player.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.powersound.player.R

/** Settings → Look and Feel → Icon: the launcher icon is switched by enabling one activity-alias. */
object LauncherIcons {

    data class Icon(val alias: String, val title: String, val subtitle: String, val res: Int)

    val ALL = listOf(
        Icon("IconDefault", "Default", "Adaptive icon", R.mipmap.ic_launcher),
        Icon("IconYou", "Default", "Material You colors", R.mipmap.ic_launcher_you),
        Icon("IconBlack", "Black", "Adaptive icon", R.mipmap.ic_launcher_black),
        Icon("IconWhite", "White", "Adaptive icon", R.mipmap.ic_launcher_white),
        Icon("IconBlue", "Blue", "Adaptive icon", R.mipmap.ic_launcher_blue),
        Icon("IconDisc", "Disc", "Adaptive icon", R.mipmap.ic_launcher_disc),
        Icon("IconGold", "Gold", "Adaptive icon", R.mipmap.ic_launcher_gold)
    )

    fun apply(context: Context, index: Int) {
        val pm = context.packageManager
        val chosen = ALL.getOrElse(index) { ALL.first() }
        // enable the new one first so the app always has a launcher entry
        ALL.sortedBy { if (it == chosen) 0 else 1 }.forEach { icon ->
            val state = if (icon == chosen) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            runCatching {
                pm.setComponentEnabledSetting(
                    ComponentName(context.packageName, "${context.packageName}.${icon.alias}"),
                    state,
                    PackageManager.DONT_KILL_APP
                )
            }
        }
    }
}
