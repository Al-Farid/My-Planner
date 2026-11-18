package io.github.alfarid.planner

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/** Invisible step that opens the planner when a widget row is tapped. */
class OpenPlannerActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Config.PLANNER_URL)))
        } catch (e: Exception) { /* no browser */ }
        finish()
    }
}
