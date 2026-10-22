package com.hapi.autobot

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        col.addView(TextView(this).apply { text = "Hapi Auto Bot"; textSize = 24f })
        status = TextView(this).apply { textSize = 15f; setPadding(0, pad / 2, 0, pad / 2) }
        col.addView(status)

        col.addView(TextView(this).apply { text = "Target app"; textSize = 14f })
        val group = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val current = Cfg.key(this)
        for ((k, _) in Cfg.APPS) {
            group.addView(RadioButton(this).apply {
                text = k.replaceFirstChar { it.uppercase() }
                id = k.hashCode()
                isChecked = (k == current)
                setOnClickListener { Cfg.setKey(this@MainActivity, k) }
            })
        }
        col.addView(group)

        col.addView(Button(this).apply {
            text = "1) Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "App info (for 'Allow restricted settings')"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName"))
                )
            }
        })

        col.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, pad, 0, 0)
            text = "Steps:\n" +
                "1. Accessibility > Installed apps > Hapi Auto Bot > turn ON.\n" +
                "   (Android 13+: if it is greyed out, open App info > ⋮ menu > " +
                "Allow restricted settings, then try again.)\n" +
                "2. A small panel with START appears on screen.\n" +
                "3. Tap START: the Hapi app opens and the bot clicks the Go button " +
                "next to Room ID. Tap STOP to pause.\n" +
                "4. Drag the panel by its text if it covers something."
        })

        setContentView(ScrollView(this).apply { addView(col) })
    }

    override fun onResume() {
        super.onResume()
        status.text = if (HapiService.instance != null)
            "Service: ON  (use the floating START button)"
        else
            "Service: OFF  (enable it in Accessibility settings)"
    }
}
