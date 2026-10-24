package com.carter.glasslauncher.ui

import android.graphics.Color
import android.media.tv.TvContract
import android.media.tv.TvView
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.carter.glasslauncher.R
import com.carter.glasslauncher.data.HdmiInputs

/**
 * Full-screen passthrough of the selected HDMI port. The Xbox dock tile defaults to HDMI 1.
 *
 * Fire OS gates every one of its own input-switching entry points behind signature-level
 * permissions (`com.amazon.tv.permission.TUNE_INPUT`, `...INPUT_SERVICE`), so none of them
 * are reachable from a third-party launcher. The platform [TvView] API is, and tuning it to
 * the input's passthrough channel puts the HDMI signal on screen directly.
 */
class HdmiInputActivity : AppCompatActivity() {

    private lateinit var message: TextView
    private lateinit var tvView: TvView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)
        setContentView(container, ViewGroup.LayoutParams(MATCH, MATCH))

        tvView = TvView(this)
        container.addView(tvView, FrameLayout.LayoutParams(MATCH, MATCH))

        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            alpha = 0.75f
            textSize = 20f
            text = getString(R.string.hdmi_no_signal)
        }
        container.addView(
            message,
            FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER)
        )

        val requested = intent.getStringExtra(EXTRA_INPUT_ID)
        val ports = HdmiInputs.list(this)
        val input = ports.firstOrNull { it.id == requested } ?: ports.firstOrNull()
        if (input == null) {
            Log.w(TAG, "no HDMI 1 input on this device")
            finish()
            return
        }

        val inputId = input.id
        message.text = getString(R.string.hdmi_no_signal_named, input.name)
        tvView.setCallback(object : TvView.TvInputCallback() {
            override fun onConnectionFailed(inputId: String) {
                Log.w(TAG, "onConnectionFailed($inputId)")
                finish()
            }

            override fun onVideoAvailable(inputId: String) {
                message.visibility = android.view.View.GONE
            }

            override fun onVideoUnavailable(inputId: String, reason: Int) {
                // reason 2 is a weak/absent signal, i.e. the console is off or asleep.
                message.visibility = android.view.View.VISIBLE
            }
        })
        tvView.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId))
    }

    override fun onDestroy() {
        if (::tvView.isInitialized) tvView.reset()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_INPUT_ID = "hdmi_input_id"
        private const val TAG = "HdmiInputActivity"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
