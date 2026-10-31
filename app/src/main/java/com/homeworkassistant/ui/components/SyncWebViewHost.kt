package com.homeworkassistant.ui.components

import android.app.Activity
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.webkit.WebView
import android.widget.FrameLayout
import com.homeworkassistant.data.web.WebSessionController

/** Clip the worker to a tiny noninteractive container, but give the document a real screen viewport. */
class SyncWebViewHost(context: Context, private val controller: WebSessionController) : FrameLayout(context) {
    private val web = WebView(context)
    init {
        clipChildren = true; clipToPadding = true; alpha = 0f
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        web.isFocusable = false; web.isClickable = false
        addView(web); controller.attach(web)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val decor = (context as? Activity)?.window?.decorView
        val metrics = resources.displayMetrics
        val width = decor?.width?.takeIf { it > 0 } ?: metrics.widthPixels
        val height = decor?.height?.takeIf { it > 0 } ?: metrics.heightPixels
        web.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        setMeasuredDimension(resolveSize(1, widthMeasureSpec), resolveSize(1, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        web.layout(0, 0, web.measuredWidth, web.measuredHeight)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = false
    fun release() { controller.detach(web); removeView(web); web.stopLoading(); com.homeworkassistant.data.web.OfficialHomeworkReader.detach(web); web.destroy() }
}
