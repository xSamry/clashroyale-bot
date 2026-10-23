package app.mergetactics.helper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dp = resources.displayMetrics.density
        val pad = (20 * dp).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Merge Tactics Helper"; textSize = 24f })
        root.addView(TextView(this).apply {
            text = "Оверлей-подсказчик для Clash Royale (Merge Tactics). Читает экран, подсказывает покупки, " +
                "слияния и сферы. Сам ничего не нажимает — решения за тобой."
            setPadding(0, pad / 2, 0, pad / 2)
        })
        status = TextView(this)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "1. Разрешить поверх других окон"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        })
        root.addView(Button(this).apply {
            text = "2. Запустить помощника"
            setOnClickListener { start() }
        })
        root.addView(TextView(this).apply {
            text = "Свои данные о мете: положи meta.json в\n${getExternalFilesDir(null)?.absolutePath}"
            textSize = 12f
            setPadding(0, pad, 0, 0)
        })
        setContentView(root)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        status.text = if (Settings.canDrawOverlays(this)) "✅ Разрешение «поверх окон» выдано"
        else "❌ Нужно разрешение «поверх других окон»"
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Сначала выдай разрешение (кнопка 1)", Toast.LENGTH_LONG).show()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK && data != null) {
            startForegroundService(
                Intent(this, OverlayService::class.java).putExtra("code", resultCode).putExtra("data", data)
            )
            moveTaskToBack(true) // уходим в фон — открывай Clash Royale
        }
    }
}
