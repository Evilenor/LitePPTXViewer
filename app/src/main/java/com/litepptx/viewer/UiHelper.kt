package com.litepptx.viewer

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView

/** Small UI helpers shared across activities so none of this logic is duplicated. */
object UiHelper {

    fun applyInsets(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            insets
        }
    }

    fun setKeepAwake(a: Activity, awake: Boolean) {
        if (awake) a.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    // ------------------------------------------------------------------ night mode

    fun isNightMode(ctx: Context): Boolean =
        ctx.getSharedPreferences("lite", Context.MODE_PRIVATE).getBoolean("night_mode", false)

    fun setNightMode(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences("lite", Context.MODE_PRIVATE).edit().putBoolean("night_mode", on).apply()
    }

    /** Perceived brightness, 0 (black) to 255 (white). */
    fun luminance(color: Int): Float =
        0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)

    /**
     * Dark document text, lightened so it stays readable on a dark background.
     * Light text is left alone, so colours survive the mapping.
     */
    fun nightTextColor(color: Int): Int {
        if (luminance(color) >= 140f) return color
        val t = 0.75f
        fun ch(c: Int) = (c + (255 - c) * t).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }

    /** Light cell fill, darkened so it stays readable on a dark background. */
    fun nightFillColor(fill: Int): Int {
        if (luminance(fill) <= 140f) return fill
        val t = 0.75f
        fun ch(c: Int) = (c * (1f - t)).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(fill)), ch(Color.green(fill)), ch(Color.blue(fill)))
    }

    fun displayName(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (i >= 0) return c.getString(i)
                    }
                }
            } catch (_: Exception) {
            }
        }
        return uri.lastPathSegment
    }

    /** Shows a slider + number jump dialog for 1-based slide/page navigation. */
    fun showGoToDialog(ctx: Context, titleRes: Int, count: Int, currentZero: Int, onJump: (Int) -> Unit) {
        if (count <= 1) return
        val dp = ctx.resources.displayMetrics.density
        val pad = (20 * dp).toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (12 * dp).toInt(), pad, 0)
        }
        val input = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            gravity = Gravity.CENTER
            hint = ctx.getString(R.string.goto_hint, count)
            setText((currentZero + 1).toString())
            setSelection(text.length)
        }
        val seek = SeekBar(ctx).apply {
            max = count - 1
            progress = currentZero.coerceIn(0, count - 1)
            setPadding(0, (14 * dp).toInt(), 0, (6 * dp).toInt())
        }
        val label = TextView(ctx).apply {
            gravity = Gravity.CENTER
            text = "${currentZero + 1} / $count"
            textSize = 13f
            setTextColor(ctx.resources.getColor(R.color.text_secondary))
        }
        var syncing = false
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                label.text = "${p + 1} / $count"
                if (fromUser && !syncing) {
                    syncing = true
                    input.setText((p + 1).toString())
                    input.setSelection(input.text.length)
                    syncing = false
                }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (syncing) return
                val n = s?.toString()?.toIntOrNull() ?: return
                syncing = true
                seek.progress = (n - 1).coerceIn(0, count - 1)
                syncing = false
            }
        })
        box.addView(input)
        box.addView(seek)
        box.addView(label)

        AlertDialog.Builder(ctx)
            .setTitle(titleRes)
            .setView(box)
            .setPositiveButton(R.string.goto_go) { _, _ ->
                val typed = input.text.toString().toIntOrNull()
                val target = if (typed != null) (typed - 1).coerceIn(0, count - 1) else seek.progress
                onJump(target)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
