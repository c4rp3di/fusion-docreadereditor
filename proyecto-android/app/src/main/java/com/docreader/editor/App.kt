package com.docreader.editor

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.HiltAndroidApp
import java.io.File

@HiltAndroidApp
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        val theme = getSharedPreferences("prefs", MODE_PRIVATE).getInt("theme", 0)
        AppCompatDelegate.setDefaultNightMode(
            when (theme) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        // Privacidad: elimina temporales de sesiones anteriores (copias de trabajo de PDF, exportaciones para compartir...)
        Thread {
            try {
                File(cacheDir, "pdfwork").deleteRecursively()
                File(cacheDir, "share").deleteRecursively()
                cacheDir.listFiles { f -> f.name.startsWith("merge_") || f.name.startsWith("idx") }?.forEach { it.delete() }
            } catch (_: Exception) { }
        }.start()
    }
}
