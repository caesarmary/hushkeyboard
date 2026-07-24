package com.hushkeyboard

import android.graphics.Typeface
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar

// Displays a bundled plain-text legal document (privacy policy or third-party credits)
// read straight from assets/legal/. Reused for both via the EXTRA_ASSET_PATH/EXTRA_TITLE
// intent extras rather than two near-identical activities.
class LegalActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ASSET_PATH = "extra_asset_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_legal)

        findViewById<MaterialToolbar>(R.id.toolbar_legal).apply {
            title = intent.getStringExtra(EXTRA_TITLE)
            setNavigationOnClickListener { finish() }
        }

        val assetPath = intent.getStringExtra(EXTRA_ASSET_PATH) ?: return
        val text = assets.open(assetPath).bufferedReader().use { it.readText() }
        findViewById<TextView>(R.id.text_legal_body).text = styledLegalText(text)
    }

    // Gives the ALL-CAPS section headers in the legal text (e.g. "WHAT WE COLLECT")
    // real visual hierarchy — bold, accent-colored, slightly larger, with breathing
    // room above — instead of rendering as plain body text. Detection logic
    // (LegalTextFormatter.isHeaderLine) is pure and unit-tested; the Spannable
    // building here is Android-coupled glue (Gate 8 Option 1 + Option 3).
    private fun styledLegalText(text: String): SpannableStringBuilder {
        val accent = ContextCompat.getColor(this, R.color.accent)
        val builder = SpannableStringBuilder()
        text.lines().forEachIndexed { index, line ->
            if (LegalTextFormatter.isHeaderLine(line)) {
                if (index > 0) builder.append("\n\n")
                val headerStart = builder.length
                builder.append(line)
                builder.setSpan(StyleSpan(Typeface.BOLD), headerStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(accent), headerStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(1.05f), headerStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else {
                if (index > 0) builder.append("\n")
                builder.append(line)
            }
        }
        return builder
    }
}
