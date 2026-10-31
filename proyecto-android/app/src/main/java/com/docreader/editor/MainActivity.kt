package com.docreader.editor

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.os.bundleOf
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.docreader.editor.data.repository.DocumentRepository
import com.docreader.editor.ui.ShortcutHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var repo: DocumentRepository

    private var lastActive = 0L
    private var unlocked = false
    private var prompting = false
    private val prefs get() = getSharedPreferences("prefs", Context.MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.btnUnlock).setOnClickListener { promptUnlock() }
        if (savedInstanceState == null) findViewById<View>(R.id.nav_host).post { handleIntent(intent) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // ------------------------------------------------------------ bloqueo y capturas
    override fun onPause() {
        super.onPause()
        lastActive = SystemClock.elapsedRealtime()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("secure", false)) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val overlay = findViewById<View>(R.id.lockOverlay)
        if (!prefs.getBoolean("lock", false)) { overlay.visibility = View.GONE; return }
        val recent = lastActive != 0L && SystemClock.elapsedRealtime() - lastActive < 30_000
        if (unlocked && recent) return
        unlocked = false
        overlay.visibility = View.VISIBLE
        promptUnlock()
    }

    private fun promptUnlock() {
        if (prompting) return
        val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { unlockNow(); return }
        prompting = true
        val bp = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { prompting = false; unlockNow() }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false }
        })
        bp.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("Desbloquear DocReader").setAllowedAuthenticators(auth).build()
        )
    }

    private fun unlockNow() {
        unlocked = true
        findViewById<View>(R.id.lockOverlay).visibility = View.GONE
    }

    // ------------------------------------------------------------ atajos de teclado (Ctrl + tecla)
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && e.isCtrlPressed && e.repeatCount == 0) {
            val f = (supportFragmentManager.findFragmentById(R.id.nav_host) as? NavHostFragment)
                ?.childFragmentManager?.primaryNavigationFragment
            if ((f as? ShortcutHandler)?.onShortcut(e.keyCode) == true) return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ------------------------------------------------------------ abrir desde otras apps
    /** Abre lo que llegue desde otras apps: «Abrir con», «Editar con» o «Compartir». */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val uris = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> i.data?.let { uris.add(it) }
            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.add(it) }
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(i, Intent.EXTRA_STREAM, Uri::class.java)?.let { uris.addAll(it) }
            else -> return
        }
        if (uris.isEmpty()) {
            Toast.makeText(this, "No hay ningún archivo que abrir", Toast.LENGTH_SHORT).show()
            return
        }
        val flags = i.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        for (u in uris) {
            val persisted = flags != 0 && try {
                contentResolver.takePersistableUriPermission(u, flags); true
            } catch (e: Exception) { false }
            if (persisted) lifecycleScope.launch { repo.import(u); repo.indexText(u) }
        }
        val nav = (supportFragmentManager.findFragmentById(R.id.nav_host) as? NavHostFragment)?.navController ?: return
        nav.navigate(
            R.id.documentViewerFragment,
            bundleOf("uri" to uris.first().toString()),
            NavOptions.Builder().setPopUpTo(R.id.documentListFragment, false).build()
        )
        i.action = Intent.ACTION_MAIN
    }
}
