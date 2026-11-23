package com.example.douyinfloat

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 常驻悬浮窗服务：
 *   点球 -> 输入框 -> 提交 -> 球变色 -> 再点开 -> 地址 + 复制图标。
 * 窗口尺寸紧贴可见内容，透明空白区不会挡住下层应用。
 */
class FloatService : Service() {

    private enum class Status { IDLE, LOADING, OK, ERROR }
    private enum class Mode { BUBBLE, INPUT, RESULT }

    private lateinit var wm: WindowManager
    private lateinit var root: View
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var bubble: View
    private lateinit var bubbleIcon: ImageView
    private lateinit var spinner: ProgressBar
    private lateinit var panelInput: View
    private lateinit var panelResult: View
    private lateinit var linkInput: EditText
    private lateinit var resultText: EditText
    private lateinit var copyBtn: ImageButton

    private val parser = DouyinParser()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var status = Status.IDLE
    private var mode = Mode.BUBBLE
    private var urls: List<String> = emptyList()
    private var lastInput = ""

    private var dragStartX = 0
    private var dragStartY = 0
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var dragged = false

    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int): Int = (v * density).roundToInt()

    companion object {
        private const val CHANNEL_ID = "float_parser"
        private const val NOTIFICATION_ID = 1
    }

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        inflateViews()
        addWindow()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (::root.isInitialized && root.isAttachedToWindow) {
            try { wm.removeView(root) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        // 先落到局部变量再链式调用：if 表达式后直接 .xxx() 会被解析进 else 分支
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(R.drawable.ic_bubble_arrow)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    @SuppressLint("InflateParams", "ClickableViewAccessibility")
    private fun inflateViews() {
        root = LayoutInflater.from(this).inflate(R.layout.float_window, null)
        bubble = root.findViewById(R.id.bubble)
        bubbleIcon = root.findViewById(R.id.bubble_icon)
        spinner = root.findViewById(R.id.spinner)
        panelInput = root.findViewById(R.id.panel_input)
        panelResult = root.findViewById(R.id.panel_result)
        linkInput = root.findViewById(R.id.link_input)
        resultText = root.findViewById(R.id.result_text)
        copyBtn = root.findViewById(R.id.btn_copy)

        resultText.keyListener = null   // 只读，但保留长按选中/复制

        bubble.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#3B6EA8"))
        }

        bubble.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartX = params.x
                    dragStartY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchStartX).roundToInt()
                    val dy = (event.rawY - touchStartY).roundToInt()
                    if (abs(dx) > dp(6) || abs(dy) > dp(6)) dragged = true
                    params.x = (dragStartX + dx).coerceIn(0, maxX(params.width))
                    params.y = (dragStartY + dy).coerceIn(0, maxY(params.height))
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onBubbleClick()
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        bubble.setOnLongClickListener {
            showMenu()
            true
        }

        root.findViewById<ImageButton>(R.id.btn_go).setOnClickListener { submit() }
        linkInput.setOnEditorActionListener { _, _, _ -> submit(); true }
        linkInput.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_UP) {
                submit(); true
            } else false
        }
        resultText.setOnClickListener { resultText.selectAll() }
        copyBtn.setOnClickListener { copyToClipboard() }
    }

    private fun addWindow() {
        params = WindowManager.LayoutParams(
            dp(56), dp(56),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(120)
        }
        wm.addView(root, params)
        render()
    }

    private fun maxX(width: Int): Int = (resources.displayMetrics.widthPixels - width).coerceAtLeast(0)
    private fun maxY(height: Int): Int = (resources.displayMetrics.heightPixels - height).coerceAtLeast(0)

    private fun resize(widthDp: Int, heightDp: Int) {
        val w = dp(widthDp)
        val h = dp(heightDp)
        var changed = false
        if (params.width != w) { params.width = w; changed = true }
        if (params.height != h) { params.height = h; changed = true }
        if (params.x > maxX(w)) { params.x = maxX(w); changed = true }
        if (params.y > maxY(h)) { params.y = maxY(h); changed = true }
        if (changed) wm.updateViewLayout(root, params)
    }

    private fun render() {
        val color = when (status) {
            Status.IDLE -> "#3B6EA8"
            Status.LOADING -> "#D08A2C"
            Status.OK -> "#2F9E63"
            Status.ERROR -> "#C8544B"
        }
        (bubble.background as? GradientDrawable)?.setColor(Color.parseColor(color))
        spinner.visibility = if (status == Status.LOADING) View.VISIBLE else View.GONE
        bubbleIcon.visibility = if (status == Status.LOADING) View.GONE else View.VISIBLE
        panelInput.visibility = if (mode == Mode.INPUT) View.VISIBLE else View.GONE
        panelResult.visibility = if (mode == Mode.RESULT) View.VISIBLE else View.GONE
        when (mode) {
            Mode.BUBBLE -> resize(56, 56)
            Mode.INPUT -> resize(348, 60)
            Mode.RESULT -> resize(396, 140)
        }
    }

    private fun onBubbleClick() {
        if (status == Status.LOADING) return
        if (mode != Mode.BUBBLE) {
            collapse()
            return
        }
        if (status == Status.OK && urls.isNotEmpty()) openResult() else openInput()
    }

    private fun openInput() {
        mode = Mode.INPUT
        linkInput.setText(lastInput)
        linkInput.setSelection(linkInput.text.length)
        // 悬浮窗默认带 FLAG_NOT_FOCUSABLE：不去掉，EditText 拿不到焦点、输入法也弹不出来。
        // 展开输入时临时去掉，收起时再恢复，保证球本身不抢焦点。
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        wm.updateViewLayout(root, params)
        render()
        linkInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(linkInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun restoreFocusFlags() {
        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        params.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        hideKeyboard()
        wm.updateViewLayout(root, params)
    }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(linkInput.windowToken, 0)
        } catch (_: Exception) {
            // windowToken 可能还没就绪，忽略
        }
    }

    private fun openResult() {
        mode = Mode.RESULT
        resultText.setText(urls.joinToString("\n"))
        copyBtn.setImageResource(R.drawable.ic_copy)
        render()
    }

    private fun collapse() {
        mode = Mode.BUBBLE
        restoreFocusFlags()
        render()
    }

    private fun submit() {
        val text = linkInput.text.toString().trim()
        if (text.isEmpty() || status == Status.LOADING) return
        lastInput = text
        hideKeyboard()
        mode = Mode.BUBBLE
        status = Status.LOADING
        restoreFocusFlags()
        render()

        // 网络请求必须离开主线程，结果再 post 回主线程刷新 UI
        Thread {
            val result = try {
                parser.parse(text)
            } catch (e: Exception) {
                null
            }
            mainHandler.post {
                if (result != null && result.urls.isNotEmpty()) {
                    urls = result.urls
                    status = Status.OK
                } else {
                    status = Status.ERROR
                }
                render()
            }
        }.start()
    }

    private fun copyToClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("douyin-url", resultText.text.toString()))
        copyBtn.setImageResource(R.drawable.ic_check)
        mainHandler.postDelayed({ copyBtn.setImageResource(R.drawable.ic_copy) }, 1400)
    }

    private fun showMenu() {
        val themed = ContextThemeWrapper(this, R.style.AppTheme)
        val popup = PopupMenu(themed, bubble)
        popup.menu.add(0, 1, 0, getString(R.string.menu_reset))
        popup.menu.add(0, 2, 0, getString(R.string.menu_exit))
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> { reset(); true }
                2 -> { stopSelf(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun reset() {
        urls = emptyList()
        lastInput = ""
        linkInput.setText("")
        resultText.setText("")
        status = Status.IDLE
        collapse()
    }
}
