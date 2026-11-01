package com.bbcl.snapsave

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.bbcl.snapsave.download.Downloads

/**
 * Target of "Share → SnapSave". Hands the link to [MainActivity], which opens in front
 * (so the video in the app the user shared from pauses) and asks for Video/Audio and a quality.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.takeIf { it.action == Intent.ACTION_SEND }?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = Downloads.extractUrl(text)
        if (url == null) {
            Toast.makeText(this, "No video link found", Toast.LENGTH_SHORT).show()
        } else {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_URL, url)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        finish()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}
