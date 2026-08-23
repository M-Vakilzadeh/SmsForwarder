package cn.ppps.forwarder.activity

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.utils.AppLockUtils
import cn.ppps.forwarder.utils.XToastUtils

/**
 * 应用锁界面：输入密码才能进入 App。界面用代码构建，避免再引入一个布局文件。
 * 密码正确 → 置 App.appUnlocked=true 并 finish()，回到被它遮住的界面（此时已解锁）。
 * 返回键不放行，改为退到后台，防止绕过。
 */
class AppLockActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (resources.displayMetrics.density * 24).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }

        val title = TextView(this).apply {
            text = getString(R.string.app_lock_enter_password)
            textSize = 18f
            gravity = Gravity.CENTER
        }

        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.app_lock_password_hint)
        }

        val unlock = Button(this).apply {
            text = getString(R.string.app_lock_unlock)
            setOnClickListener {
                if (AppLockUtils.verify(input.text.toString())) {
                    App.appUnlocked = true
                    finish()
                } else {
                    XToastUtils.error(getString(R.string.app_lock_wrong))
                    input.setText("")
                }
            }
        }

        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = pad / 2
        }
        root.addView(title, lp)
        root.addView(input, lp)
        root.addView(unlock, lp)
        setContentView(root)
    }

    //返回键不放行，退到后台，避免绕过应用锁
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
