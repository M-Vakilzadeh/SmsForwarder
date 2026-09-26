package cn.ppps.forwarder.activity

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import cn.ppps.forwarder.R
import cn.ppps.forwarder.utils.CallNotePopup

/**
 * 点击「通话备注」通知后打开的表单（对话框样式），与悬浮窗共用 dialog_call_note 布局。
 * 不需要悬浮窗权限；用户点击通知属于前台启动，不受后台启动 Activity 的限制。
 */
class CallNoteActivity : Activity() {

    private var prompt: CallNotePopup.Prompt? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = CallNotePopup.promptFromJson(intent.getStringExtra(CallNotePopup.EXTRA_PROMPT))
        if (p == null) {
            finish()
            return
        }
        prompt = p
        setFinishOnTouchOutside(false)
        setContentView(R.layout.dialog_call_note)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        //adjustResize 已在 Manifest 声明
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        CallNotePopup.bindForm(window.decorView.findViewById(android.R.id.content), p,
            onSend = { text ->
                CallNotePopup.markHandled(this, p.key)
                CallNotePopup.submit(this, p, text)
                finish()
            },
            //稍后：通知保留，下次点开继续写
            onLater = { finish() },
            onSkip = {
                CallNotePopup.markHandled(this, p.key)
                CallNotePopup.cancelNotification(this, p.key)
                finish()
            }).requestFocus()
    }
}
