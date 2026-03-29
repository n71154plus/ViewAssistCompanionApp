package com.msp1974.vacompanion.launcher

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.msp1974.vacompanion.MainActivity

/**
 * Trampoline activity that brings the HA WebView (MainActivity) to the foreground
 * and finishes immediately, so the back-stack remains clean.
 */
class HADashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        startActivity(intent)
        finish()
    }

    companion object {
        fun buildIntent(context: Context): Intent =
            Intent(context, HADashboardActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
    }
}
