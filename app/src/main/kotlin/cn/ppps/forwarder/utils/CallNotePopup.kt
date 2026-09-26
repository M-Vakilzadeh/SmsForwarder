package cn.ppps.forwarder.utils

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import cn.ppps.forwarder.R
import cn.ppps.forwarder.activity.CallNoteActivity
import cn.ppps.forwarder.entity.CallNote
import cn.ppps.forwarder.workers.CallNoteWorker
import com.google.gson.Gson
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 通话结束后的「通话备注」弹窗。
 *
 * 展示方式（设置可选）：
 * - 弹窗：用悬浮窗（TYPE_APPLICATION_OVERLAY）直接盖在任何界面上，App 是否打开都能弹出。
 *   不用 startActivity：Android 10+ 后台启动 Activity 受限，MIUI 还有独立的「后台弹出界面」开关会静默拦截；
 *   悬浮窗只需要「显示在其他应用上层」权限。没有该权限 / 锁屏时（悬浮窗画不到锁屏上）自动退化为通知。
 * - 通知：发一条高优先级通知，点击后打开 CallNoteActivity（同一张表单）。
 *
 * 来电响铃 / 拨出新电话时，悬浮窗会先收起（保留已输入内容），避免挡住接听键；本通电话结束后再弹回。
 * 所有 UI 操作都在主线程。
 */
object CallNotePopup {

    private const val TAG = "CallNotePopup"
    private const val CHANNEL_ID = "call_note"
    const val EXTRA_PROMPT = "call_note_prompt"

    //一次待写备注的通话；key=开始时刻，用于去重与关联通知
    data class Prompt(
        val callType: Int,
        val number: String,
        val contactName: String,
        val callStart: Long,
        val callEnd: Long,
        var draft: String = "",
    ) {
        val key: Long get() = callStart
    }

    private val main = Handler(Looper.getMainLooper())
    private val gson = Gson()

    //等待以悬浮窗展示的备注（按通话结束先后）
    private val pending = ArrayDeque<Prompt>()
    private var current: Prompt? = null
    private var currentView: View? = null
    private var currentEdit: EditText? = null

    //通话进行中：不弹悬浮窗
    private var inCall = false

    //来电响铃 / 拨出：收起悬浮窗，保留草稿
    fun onCallStarted(context: Context) {
        main.post {
            inCall = true
            val p = current ?: return@post
            p.draft = currentEdit?.text?.toString() ?: p.draft
            removeCurrentView(context)
            pending.addFirst(p)
        }
    }

    //任意一通电话结束（含未接）：恢复被收起的悬浮窗；接通过的来电/去电再新增一张备注
    fun onCallEnded(context: Context, callType: Int, number: String?, start: Date, end: Date) {
        val app = context.applicationContext
        val prompt = if (SettingUtils.callNoteEnabled && SettingUtils.callNoteUrl.isNotBlank() && CallNoteUtils.shouldPrompt(callType)) {
            val num = number ?: ""
            Prompt(callType, num, lookupContact(num), start.time, end.time)
        } else null

        main.post {
            inCall = false
            if (prompt != null) {
                if (SettingUtils.callNoteDisplayMode == CALL_NOTE_DISPLAY_POPUP && canDrawOverlays(app)) {
                    if (pending.none { it.key == prompt.key } && current?.key != prompt.key) pending.addLast(prompt)
                    //锁屏时悬浮窗画不到锁屏上，同时发通知兜底
                    if (isLocked(app)) postNotification(app, prompt)
                } else {
                    postNotification(app, prompt)
                }
            }
            showNext(app)
        }
    }

    //备注已在别处（通知打开的 Activity）处理：撤掉对应的悬浮窗
    fun markHandled(context: Context, key: Long) {
        main.post {
            pending.removeAll { it.key == key }
            if (current?.key == key) {
                removeCurrentView(context)
                showNext(context.applicationContext)
            }
        }
    }

    //写入待发送队列并按发送方式触发发送
    fun submit(context: Context, prompt: Prompt, text: String) {
        val app = context.applicationContext
        val note = CallNote(
            noteId = UUID.randomUUID().toString(),
            deviceMark = SettingUtils.extraDeviceMark,
            number = prompt.number,
            contactName = prompt.contactName,
            callType = prompt.callType,
            callStart = prompt.callStart,
            callEnd = prompt.callEnd,
            note = text.trim(),
            createdAt = System.currentTimeMillis(),
        )
        try {
            CallNoteStore.add(app, note)
            CallNoteWorker.onNoteQueued(app)
            cancelNotification(app, prompt.key)
            XToastUtils.success(app.getString(R.string.call_note_saved))
        } catch (e: Exception) {
            Log.e(TAG, "保存通话备注失败：${e.message}", e)
            XToastUtils.error(app.getString(R.string.call_note_save_failed))
        }
    }

    fun promptToJson(prompt: Prompt): String = gson.toJson(prompt)
    fun promptFromJson(json: String?): Prompt? = try {
        if (json.isNullOrBlank()) null else gson.fromJson(json, Prompt::class.java)
    } catch (e: Exception) {
        null
    }

    /**
     * 绑定表单（悬浮窗与 Activity 共用）。
     */
    fun bindForm(root: View, prompt: Prompt, onSend: (String) -> Unit, onLater: (String) -> Unit, onSkip: () -> Unit): EditText {
        val ctx = root.context
        root.findViewById<TextView>(R.id.tv_call_note_info).text = describe(ctx, prompt)
        val edit = root.findViewById<EditText>(R.id.et_call_note)
        edit.setText(prompt.draft)
        edit.setSelection(edit.text.length)
        root.findViewById<Button>(R.id.btn_call_note_send).setOnClickListener { onSend(edit.text.toString()) }
        root.findViewById<Button>(R.id.btn_call_note_later).setOnClickListener { onLater(edit.text.toString()) }
        root.findViewById<Button>(R.id.btn_call_note_skip).setOnClickListener { onSkip() }
        return edit
    }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun cancelNotification(context: Context, key: Long) {
        try {
            notificationManager(context).cancel(notifyId(key))
        } catch (e: Exception) {
            Log.w(TAG, "取消通知失败：${e.message}")
        }
    }

    private fun showNext(context: Context) {
        if (inCall || current != null) return
        val prompt = pending.removeFirstOrNull() ?: return
        if (!canDrawOverlays(context)) {
            //权限在此期间被收回：退化为通知
            postNotification(context, prompt)
            showNext(context)
            return
        }
        try {
            addOverlay(context, prompt)
        } catch (e: Exception) {
            Log.e(TAG, "悬浮窗弹出失败，改用通知：${e.message}", e)
            current = null
            currentView = null
            currentEdit = null
            postNotification(context, prompt)
        }
    }

    @SuppressLint("InflateParams")
    private fun addOverlay(context: Context, prompt: Prompt) {
        val themed = ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light)
        //根布局拦截返回键：等同「稍后」，收进通知栏
        val root = object : FrameLayout(themed) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) later(context, currentEdit?.text?.toString() ?: "")
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        val pad = (12 * context.resources.displayMetrics.density).toInt()
        root.setPadding(pad, pad, pad, pad)
        val form = LayoutInflater.from(themed).inflate(R.layout.dialog_call_note, root, false)
        root.addView(form)

        val edit = bindForm(form, prompt,
            onSend = { text ->
                removeCurrentView(context)
                submit(context, prompt, text)
                showNext(context)
            },
            onLater = { text -> later(context, text) },
            onSkip = {
                removeCurrentView(context)
                cancelNotification(context, prompt.key)
                showNext(context)
            })

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            //可获取焦点（才能弹出键盘），但窗口外的触摸照常传给下层应用，不会把手机「锁死」
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (context.resources.displayMetrics.heightPixels * 0.12).toInt()
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        }
        windowManager(context).addView(root, params)
        current = prompt
        currentView = root
        currentEdit = edit

        edit.requestFocus()
        main.postDelayed({
            try {
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            } catch (_: Exception) {
            }
        }, 250)
    }

    //稍后再写：收起悬浮窗，保留草稿，放进通知栏
    private fun later(context: Context, text: String) {
        val p = current ?: return
        p.draft = text
        removeCurrentView(context)
        postNotification(context, p)
        showNext(context)
    }

    private fun removeCurrentView(context: Context) {
        val v = currentView
        current = null
        currentView = null
        currentEdit = null
        if (v != null) {
            try {
                windowManager(context).removeView(v)
            } catch (e: Exception) {
                Log.w(TAG, "移除悬浮窗失败：${e.message}")
            }
        }
    }

    private fun postNotification(context: Context, prompt: Prompt) {
        try {
            val nm = notificationManager(context)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.call_note_channel_name), NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val intent = Intent(context, CallNoteActivity::class.java)
                .putExtra(EXTRA_PROMPT, promptToJson(prompt))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
            val pending = PendingIntent.getActivity(context, notifyId(prompt.key), intent, flags)
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_forwarder)
                .setContentTitle(context.getString(R.string.call_note_title))
                .setContentText(describe(context, prompt))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(pending)
                //点开后若选「稍后」，通知仍保留；发送/跳过时由表单主动取消
                .setAutoCancel(false)
                .setWhen(prompt.callEnd)
                .build()
            nm.notify(notifyId(prompt.key), notification)
        } catch (e: Exception) {
            Log.e(TAG, "发送通话备注通知失败：${e.message}", e)
        }
    }

    private fun describe(context: Context, prompt: Prompt): String {
        val who = listOf(prompt.contactName, prompt.number).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        val type = context.getString(if (prompt.callType == 1) R.string.call_note_incoming else R.string.call_note_outgoing)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(prompt.callStart))
        return listOf(who, "$type · $time").filter { it.isNotBlank() }.joinToString("\n")
    }

    private fun lookupContact(number: String): String {
        if (number.isBlank()) return ""
        return try {
            PhoneUtils.getContactByNumber(number).firstOrNull()?.name ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun isLocked(context: Context): Boolean = try {
        (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
    } catch (e: Exception) {
        false
    }

    //同一通电话固定同一个通知 id
    private fun notifyId(key: Long): Int = (key % 1_000_000_000L).toInt() + 1_000_000_000

    private fun notificationManager(context: Context) = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun windowManager(context: Context) = context.applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
}
