package com.voicedock.ai.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * The dim (and, where the OS allows it, blur) layer behind the assistant window.
 *
 * attach() adds the view ONCE, before the bubble, so it always sits BELOW the mic bubble in
 * WindowManager z-order. While hidden it is FLAG_NOT_TOUCHABLE + GONE: it never steals touches.
 * Android 12+ background blur is attempted reflectively because the field type differs between
 * platform versions; failure simply degrades to dim, as documented in the plan.
 */
class ScrimController(
    private val ctx: Context,
    private val wm: WindowManager,
    private val type: Int,
    private val onOutside: () -> Unit,
) {
    private var view: View? = null
    private var params: WindowManager.LayoutParams? = null

    fun attach() {
        if (view != null) return
        val v = View(ctx).apply {
            setBackgroundColor(0)
            setOnClickListener { onOutside() }
            visibility = View.GONE
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        runCatching { wm.addView(v, p) }
        view = v
        params = p
    }

    @SuppressLint("SoonBlockedPrivateApi")
    fun show(dim: Boolean, blur: Boolean) {
        val v = view ?: return
        val p = params ?: return
        v.setBackgroundColor(if (dim) 0x660A0F16.toInt() else 0)
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (blur && Build.VERSION.SDK_INT >= 31) {
            runCatching {
                val f = WindowManager.LayoutParams::class.java.getField("blurBehindRadius")
                when (f.type) {
                    Int::class.javaPrimitiveType -> f.setInt(p, 28)
                    Float::class.javaPrimitiveType -> f.setFloat(p, 28f)
                }
                p.flags = p.flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
            }
        }
        v.visibility = View.VISIBLE
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun hide() {
        val v = view ?: return
        val p = params ?: return
        v.visibility = View.GONE
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        runCatching { wm.updateViewLayout(v, p) }
    }

    fun detach() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
        params = null
    }
}
