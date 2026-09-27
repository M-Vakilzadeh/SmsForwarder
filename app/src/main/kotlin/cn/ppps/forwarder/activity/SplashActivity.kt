package cn.ppps.forwarder.activity

import android.annotation.SuppressLint
import android.view.KeyEvent
import cn.ppps.forwarder.R
import cn.ppps.forwarder.utils.SettingUtils
import com.xuexiang.xui.utils.KeyboardUtils
import com.xuexiang.xui.widget.activity.BaseSplashActivity
import com.xuexiang.xutil.app.ActivityUtils
import me.jessyan.autosize.internal.CancelAdapt

@Suppress("PropertyName")
@SuppressLint("CustomSplashScreen")
class SplashActivity : BaseSplashActivity(), CancelAdapt {

    val TAG: String = SplashActivity::class.java.simpleName

    override fun getSplashDurationMillis(): Long {
        return 500
    }

    /**
     * activity启动后的初始化
     */
    override fun onCreateActivity() {
        initSplashView(R.drawable.xui_config_bg_splash)
        startSplash(false)
    }

    /**
     * Called when the splash screen ends
     */
    override fun onSplashFinished() {
        //Skip upstream SmsForwarder's privacy-policy popup: go straight to the app.
        //isAgreePrivacy is left untouched (it only gates UMeng analytics).
        whereToJump()
    }

    private fun whereToJump() {
        if (SettingUtils.enablePureTaskMode) {
            ActivityUtils.startActivity(TaskActivity::class.java)
        } else if (SettingUtils.enablePureClientMode) {
            ActivityUtils.startActivity(ClientActivity::class.java)
        } else {
            ActivityUtils.startActivity(MainActivity::class.java)
        }
        finish()
    }

    /**
     * 菜单、返回键响应
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        return KeyboardUtils.onDisableBackKeyDown(keyCode) && super.onKeyDown(keyCode, event)
    }
}