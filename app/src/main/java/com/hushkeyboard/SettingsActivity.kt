package com.hushkeyboard

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.CompoundButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

// The preference file name and key are shared constants so HushKeyboardService
// and SettingsActivity always read from and write to the same location.
internal const val PREFS_NAME = "hush_settings"
internal const val PREFS_KEY_SUGGESTIONS = "suggestions_enabled"
internal const val PREFS_KEY_AUTOCORRECT = "autocorrect_enabled"
internal const val PREFS_KEY_AUTOCAPS = "autocaps_enabled"
internal const val PREFS_KEY_LONG_PRESS_DELAY = "long_press_delay_ms"
internal const val PREFS_KEY_DOUBLE_TAP_DELAY = "double_tap_delay_ms"
internal const val PREFS_KEY_BACKSPACE_REPEAT_MS = "backspace_repeat_ms"
internal const val PREFS_KEY_INVERT_NUMPAD_ENTER_BACKSPACE = "invert_numpad_enter_backspace"

// Bare https link only: no tracking/UTM params, no URL shortener (per the
// DEFINITION_OF_RIGHT donate-link conditions).
internal const val DONATE_URL = "https://www.buymeacoffee.com/caesarmary"

// Slider range for the long-press delay: 150-800 ms in 25 ms steps (SeekBar progress 0-26).
private const val LONG_PRESS_DELAY_MIN_MS = 150
private const val LONG_PRESS_DELAY_STEP_MS = 25

// Slider range for the Shift double-tap window: 150-400 ms in 25 ms steps (SeekBar
// progress 0-10). 100 ms proved too fast to reliably double-tap; 150 ms matches the
// long-press delay's minimum.
private const val DOUBLE_TAP_DELAY_MIN_MS = 150
private const val DOUBLE_TAP_DELAY_STEP_MS = 25

// Slider range for the backspace repeat rate: 100-300 ms in 25 ms steps (SeekBar
// progress 0-8). Unlike the double-tap window, this is a continuous hold-and-repeat
// motor task rather than a discrete double-tap, so 100 ms is usable here.
private const val BACKSPACE_REPEAT_MIN_MS = 100
private const val BACKSPACE_REPEAT_STEP_MS = 25

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val toggle = findViewById<CompoundButton>(R.id.switch_suggestions)

        // Reflect the stored preference; default true (suggestions on).
        toggle.isChecked = prefs.getBoolean(PREFS_KEY_SUGGESTIONS, true)

        // Write back the new value every time the user flips the switch.
        toggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_SUGGESTIONS, isChecked).apply()
        }

        val autocorrectToggle = findViewById<CompoundButton>(R.id.switch_autocorrect)
        autocorrectToggle.isChecked = prefs.getBoolean(PREFS_KEY_AUTOCORRECT, true)
        autocorrectToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_AUTOCORRECT, isChecked).apply()
        }

        val autocapsToggle = findViewById<CompoundButton>(R.id.switch_autocaps)
        autocapsToggle.isChecked = prefs.getBoolean(PREFS_KEY_AUTOCAPS, true)
        autocapsToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_AUTOCAPS, isChecked).apply()
        }

        val invertNumpadToggle = findViewById<CompoundButton>(R.id.switch_invert_numpad)
        invertNumpadToggle.isChecked = prefs.getBoolean(PREFS_KEY_INVERT_NUMPAD_ENTER_BACKSPACE, false)
        invertNumpadToggle.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREFS_KEY_INVERT_NUMPAD_ENTER_BACKSPACE, isChecked).apply()
        }

        setupLongPressDelay(prefs)
        setupDoubleTapDelay(prefs)
        setupBackspaceRepeat(prefs)
        setupClearDictionary()
        setupAboutRows()
    }

    // "About" section: opens LegalActivity with the bundled plain-text privacy policy
    // or third-party credits document. Both read from assets/legal/ at display time,
    // so updating the text only ever requires editing the asset file.
    private fun setupAboutRows() {
        findViewById<View>(R.id.row_privacy_policy).setOnClickListener {
            startActivity(
                Intent(this, LegalActivity::class.java)
                    .putExtra(LegalActivity.EXTRA_TITLE, getString(R.string.settings_privacy_policy_label))
                    .putExtra(LegalActivity.EXTRA_ASSET_PATH, "legal/privacy_policy.txt")
            )
        }
        findViewById<View>(R.id.row_credits).setOnClickListener {
            startActivity(
                Intent(this, LegalActivity::class.java)
                    .putExtra(LegalActivity.EXTRA_TITLE, getString(R.string.settings_credits_label))
                    .putExtra(LegalActivity.EXTRA_ASSET_PATH, "legal/credits.txt")
            )
        }
        setupDonateRow()
    }

    // Support-development link. Fires ACTION_VIEW to the external browser only on an
    // explicit tap — no payment SDK, no in-app WebView, no permission added. The row's
    // description shows the literal URL up front (the Session 85 DoR mitigation for
    // trusting an outbound link), sourced from the same DONATE_URL constant so there is
    // one place to update it.
    private fun setupDonateRow() {
        findViewById<TextView>(R.id.text_donate_description).text =
            getString(R.string.settings_donate_description, DONATE_URL)
        findViewById<View>(R.id.row_donate).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(DONATE_URL)))
        }
    }

    // Slider for the long-press delay (150-800 ms, 25 ms steps). Saves on every change so
    // HushKeyboardService picks it up on the next onStartInput. The "Test this duration"
    // button demonstrates the *currently selected* value live, before it's necessarily
    // been acted on elsewhere, by holding it down and reporting whether the hold lasted
    // that long.
    private fun setupLongPressDelay(prefs: android.content.SharedPreferences) {
        val seekBar = findViewById<SeekBar>(R.id.seekbar_long_press)
        val valueText = findViewById<TextView>(R.id.text_long_press_value)
        val resultText = findViewById<TextView>(R.id.text_long_press_test_result)
        val testButton = findViewById<Button>(R.id.button_long_press_test)

        val storedMs = prefs.getInt(PREFS_KEY_LONG_PRESS_DELAY, 400)
        var currentMs = storedMs
        seekBar.progress = (storedMs - LONG_PRESS_DELAY_MIN_MS) / LONG_PRESS_DELAY_STEP_MS
        valueText.text = getString(R.string.settings_long_press_value, currentMs)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                currentMs = LONG_PRESS_DELAY_MIN_MS + progress * LONG_PRESS_DELAY_STEP_MS
                valueText.text = getString(R.string.settings_long_press_value, currentMs)
                if (fromUser) {
                    prefs.edit().putInt(PREFS_KEY_LONG_PRESS_DELAY, currentMs).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val testHandler = Handler(Looper.getMainLooper())
        val fireRunnable = Runnable {
            resultText.text = getString(R.string.settings_long_press_test_fired)
        }
        testButton.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resultText.text = getString(R.string.settings_long_press_test_waiting)
                    testHandler.postDelayed(fireRunnable, currentMs.toLong())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    testHandler.removeCallbacks(fireRunnable)
                }
            }
            false
        }
    }

    // Slider for the Shift double-tap window (100-400 ms, 20 ms steps). Saves on every
    // change so HushKeyboardService picks it up on the next onStartInput.
    private fun setupDoubleTapDelay(prefs: android.content.SharedPreferences) {
        val seekBar = findViewById<SeekBar>(R.id.seekbar_double_tap)
        val valueText = findViewById<TextView>(R.id.text_double_tap_value)

        val storedMs = prefs.getInt(PREFS_KEY_DOUBLE_TAP_DELAY, 300)
        seekBar.progress = (storedMs - DOUBLE_TAP_DELAY_MIN_MS) / DOUBLE_TAP_DELAY_STEP_MS
        valueText.text = getString(R.string.settings_double_tap_value, storedMs)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val currentMs = DOUBLE_TAP_DELAY_MIN_MS + progress * DOUBLE_TAP_DELAY_STEP_MS
                valueText.text = getString(R.string.settings_double_tap_value, currentMs)
                if (fromUser) {
                    prefs.edit().putInt(PREFS_KEY_DOUBLE_TAP_DELAY, currentMs).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    // Slider for the backspace long-press repeat rate (100-300 ms, 25 ms steps). Saves on
    // every change so HushKeyboardService picks it up on the next onStartInput.
    private fun setupBackspaceRepeat(prefs: android.content.SharedPreferences) {
        val seekBar = findViewById<SeekBar>(R.id.seekbar_backspace_repeat)
        val valueText = findViewById<TextView>(R.id.text_backspace_repeat_value)

        val storedMs = prefs.getInt(PREFS_KEY_BACKSPACE_REPEAT_MS, 300)
        seekBar.progress = (storedMs - BACKSPACE_REPEAT_MIN_MS) / BACKSPACE_REPEAT_STEP_MS
        valueText.text = getString(R.string.settings_backspace_repeat_value, storedMs)

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val currentMs = BACKSPACE_REPEAT_MIN_MS + progress * BACKSPACE_REPEAT_STEP_MS
                valueText.text = getString(R.string.settings_backspace_repeat_value, currentMs)
                if (fromUser) {
                    prefs.edit().putInt(PREFS_KEY_BACKSPACE_REPEAT_MS, currentMs).apply()
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    // Clear-learned-words control. On the hardened build the learned-words feature
    // is compiled out, so the whole row is hidden. Otherwise tapping Clear shows a
    // single confirmation dialog; confirming deletes the encrypted dictionary file
    // (the one-action user clear of SECURITY.md rule 5).
    private fun setupClearDictionary() {
        val row = findViewById<View>(R.id.row_clear_dictionary)
        if (!BuildConfig.LEARNED_WORDS_ENABLED) {
            row.visibility = View.GONE
            return
        }
        findViewById<Button>(R.id.button_clear_dictionary).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_dictionary_confirm_title)
                .setMessage(R.string.settings_clear_dictionary_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.settings_clear_dictionary_button) { _, _ ->
                    LearnedWordsStore.getInstance(applicationContext).clear()
                    Toast.makeText(
                        this,
                        R.string.settings_clear_dictionary_done,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                .show()
        }
    }
}
