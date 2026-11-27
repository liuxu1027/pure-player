package `is`.xyz.mpv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * PIN gate for the private vault.
 *
 * Modes (EXTRA_MODE): "unlock" (enter the vault) or "change" (change the PIN).
 * Unlock with no PIN set goes straight to setup (enter the new PIN twice).
 * Forgot PIN restores every hidden video to its original location and clears
 * the PIN, so a forgotten PIN never loses files.
 */
class PinActivity : AppCompatActivity() {

    private var mode = MODE_UNLOCK
    private var stage = STAGE_UNLOCK
    private var pendingPin = ""
    private var entered = ""

    private lateinit var titleText: TextView
    private lateinit var msgText: TextView
    private lateinit var dots: List<TextView>
    private lateinit var keypadButtons: List<Button>
    private lateinit var forgotBtn: TextView

    private val lockHandler = Handler(Looper.getMainLooper())
    private val lockCheckRunnable = Runnable { updateLockState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        setContentView(R.layout.activity_pin)

        mode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_UNLOCK

        titleText = findViewById(R.id.pinTitle)
        msgText = findViewById(R.id.pinMessage)
        dots = listOf(
            findViewById(R.id.dot0),
            findViewById(R.id.dot1),
            findViewById(R.id.dot2),
            findViewById(R.id.dot3)
        )
        forgotBtn = findViewById(R.id.forgotPin)
        findViewById<View>(R.id.pinBack).setOnClickListener { finish() }

        val digitIds = listOf(
            R.id.key1, R.id.key2, R.id.key3,
            R.id.key4, R.id.key5, R.id.key6,
            R.id.key7, R.id.key8, R.id.key9,
            R.id.key0
        )
        val digitChars = "1234567890"
        keypadButtons = digitIds.map { findViewById<Button>(it) } +
            listOf(findViewById<Button>(R.id.keyBack))
        digitIds.forEachIndexed { i, id ->
            findViewById<Button>(id).setOnClickListener { onKey(digitChars[i]) }
        }
        findViewById<Button>(R.id.keyBack).setOnClickListener { onBackspace() }

        forgotBtn.setOnClickListener { onForgotPin() }

        when (mode) {
            MODE_CHANGE -> {
                forgotBtn.visibility = View.GONE
                if (!PinStore.hasPin(this)) {
                    stage = STAGE_ENTER_NEW
                    titleText.setText(R.string.pin_setup_title)
                } else {
                    stage = STAGE_ENTER_OLD
                    titleText.setText(R.string.pin_enter_old)
                }
            }
            else -> {
                if (!PinStore.hasPin(this)) {
                    forgotBtn.visibility = View.GONE
                    stage = STAGE_ENTER_NEW
                    titleText.setText(R.string.pin_setup_title)
                } else {
                    forgotBtn.visibility = View.VISIBLE
                    stage = STAGE_UNLOCK
                    titleText.setText(R.string.pin_unlock_title)
                }
            }
        }
        updateLockState()
        updateDots()
    }

    override fun onDestroy() {
        lockHandler.removeCallbacks(lockCheckRunnable)
        super.onDestroy()
    }

    private fun onKey(digit: Char) {
        if (entered.length >= 4) return
        entered += digit
        updateDots()
        if (entered.length == 4) {
            onPinComplete(entered)
        }
    }

    private fun onBackspace() {
        if (entered.isNotEmpty()) {
            entered = entered.dropLast(1)
            updateDots()
        }
    }

    private fun onPinComplete(pin: String) {
        when (stage) {
            STAGE_UNLOCK -> {
                if (PinStore.verifyPin(this, pin)) {
                    PinStore.resetFailures(this)
                    openVault()
                } else {
                    val n = PinStore.recordFailure(this)
                    showError(
                        if (PinStore.isLocked(this)) {
                            getString(R.string.pin_wrong_locked)
                        } else {
                            getString(R.string.pin_wrong, 5 - n)
                        }
                    )
                    entered = ""
                    updateDots()
                    updateLockState()
                }
            }
            STAGE_ENTER_OLD -> {
                if (PinStore.verifyPin(this, pin)) {
                    stage = STAGE_ENTER_NEW
                    titleText.setText(R.string.pin_setup_new)
                    msgText.text = ""
                    entered = ""
                    updateDots()
                } else {
                    showError(getString(R.string.pin_wrong_old))
                    entered = ""
                    updateDots()
                }
            }
            STAGE_ENTER_NEW -> {
                pendingPin = pin
                stage = STAGE_CONFIRM_NEW
                titleText.setText(R.string.pin_confirm_title)
                msgText.text = ""
                entered = ""
                updateDots()
            }
            STAGE_CONFIRM_NEW -> {
                if (pin == pendingPin) {
                    PinStore.setPin(this, pin)
                    Toast.makeText(this, R.string.pin_saved, Toast.LENGTH_SHORT).show()
                    if (mode == MODE_CHANGE) {
                        finish()
                    } else {
                        openVault()
                    }
                } else {
                    showError(getString(R.string.pin_mismatch))
                    stage = STAGE_ENTER_NEW
                    titleText.setText(R.string.pin_setup_new)
                    pendingPin = ""
                    entered = ""
                    updateDots()
                }
            }
        }
    }

    private fun openVault() {
        startActivity(Intent(this, VaultActivity::class.java))
        finish()
    }

    private fun onForgotPin() {
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_forgot_title)
            .setMessage(R.string.pin_forgot_message)
            .setPositiveButton(R.string.pin_forgot_confirm) { _, _ -> doForgotPin() }
            .setNegativeButton(R.string.dialog_cancel, null)
            .show()
    }

    private fun doForgotPin() {
        Thread {
            val failed = VaultStore.restoreAll(this)
            PinStore.clearPin(this)
            runOnUiThread {
                if (failed.isEmpty()) {
                    Toast.makeText(this, R.string.pin_forgot_done, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(
                        this,
                        getString(R.string.pin_forgot_partial, failed.size),
                        Toast.LENGTH_LONG
                    ).show()
                }
                finish()
            }
        }.start()
    }

    private fun updateLockState() {
        lockHandler.removeCallbacks(lockCheckRunnable)
        val remaining = PinStore.lockRemainingMs(this)
        if (remaining > 0) {
            setKeypadEnabled(false)
            val mins = (remaining / 60000).toInt()
            val secs = ((remaining % 60000) / 1000).toInt()
            msgText.text = if (mins > 0) {
                getString(R.string.pin_locked_min, mins, secs)
            } else {
                getString(R.string.pin_locked_sec, secs)
            }
            lockHandler.postDelayed(lockCheckRunnable, 1000)
        } else {
            setKeypadEnabled(true)
            if (stage == STAGE_UNLOCK || stage == STAGE_ENTER_OLD) msgText.text = ""
        }
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        for (b in keypadButtons) b.isEnabled = enabled
    }

    private fun updateDots() {
        for (i in dots.indices) {
            val filled = i < entered.length
            dots[i].text = if (filled) "●" else "○"
            dots[i].setTextColor(
                if (filled) 0xFF3C8CF0.toInt() else 0xFFBDBDBD.toInt()
            )
        }
    }

    private fun showError(msg: String) {
        msgText.text = msg
    }

    companion object {
        const val EXTRA_MODE = "mode"
        const val MODE_UNLOCK = "unlock"
        const val MODE_CHANGE = "change"

        private const val STAGE_UNLOCK = 0
        private const val STAGE_ENTER_OLD = 1
        private const val STAGE_ENTER_NEW = 2
        private const val STAGE_CONFIRM_NEW = 3
    }
}
