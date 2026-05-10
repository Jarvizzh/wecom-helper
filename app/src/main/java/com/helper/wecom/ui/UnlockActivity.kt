package com.helper.wecom.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log

class UnlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.d("UnlockActivity", "onCreate: Attempting to unlock screen")
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (km.isKeyguardLocked) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        super.onDismissSucceeded()
                        Log.d("UnlockActivity", "Keyguard dismissed successfully")
                        launchWeCom()
                    }

                    override fun onDismissError() {
                        super.onDismissError()
                        Log.e("UnlockActivity", "Keyguard dismiss error")
                        finish()
                    }

                    override fun onDismissCancelled() {
                        super.onDismissCancelled()
                        Log.e("UnlockActivity", "Keyguard dismiss cancelled")
                        finish()
                    }
                })
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
                launchWeCom()
            }
        } else {
            Log.d("UnlockActivity", "Keyguard already unlocked")
            launchWeCom()
        }
    }

    private fun launchWeCom() {
        Log.d("UnlockActivity", "Launching WeCom")
        packageManager.getLaunchIntentForPackage("com.tencent.wework")?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(it)
        }
        finish()
    }
}
