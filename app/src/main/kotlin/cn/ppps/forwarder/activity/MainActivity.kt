package cn.ppps.forwarder.activity

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.google.android.material.tabs.TabLayout
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import cn.ppps.forwarder.App
import cn.ppps.forwarder.R
import cn.ppps.forwarder.adapter.menu.DrawerAdapter
import cn.ppps.forwarder.adapter.menu.DrawerItem
import cn.ppps.forwarder.adapter.menu.SimpleItem
import cn.ppps.forwarder.adapter.menu.SpaceItem
import cn.ppps.forwarder.core.BaseActivity
import cn.ppps.forwarder.core.Core
import cn.ppps.forwarder.core.webview.AgentWebActivity
import cn.ppps.forwarder.databinding.ActivityMainBinding
import cn.ppps.forwarder.fragment.AboutFragment
import cn.ppps.forwarder.fragment.AppListFragment
import cn.ppps.forwarder.fragment.ClientFragment
import cn.ppps.forwarder.fragment.FrpcFragment
import cn.ppps.forwarder.fragment.LogsFragment
import cn.ppps.forwarder.fragment.RulesFragment
import cn.ppps.forwarder.fragment.SendersFragment
import cn.ppps.forwarder.fragment.ServerFragment
import cn.ppps.forwarder.fragment.SettingsFragment
import cn.ppps.forwarder.fragment.TasksFragment
import cn.ppps.forwarder.service.ForegroundService
import cn.ppps.forwarder.utils.ACTION_START
import cn.ppps.forwarder.utils.CommonUtils.Companion.restartApplication
import cn.ppps.forwarder.utils.EVENT_LOAD_APP_LIST
import cn.ppps.forwarder.utils.FRPC_LIB_DOWNLOAD_URL
import cn.ppps.forwarder.utils.FRPC_LIB_VERSION
import cn.ppps.forwarder.utils.Log
import cn.ppps.forwarder.utils.DefaultConfig
import cn.ppps.forwarder.utils.CALL_NOTE_DISPLAY_POPUP
import cn.ppps.forwarder.utils.SettingUtils
import cn.ppps.forwarder.utils.XToastUtils
import cn.ppps.forwarder.utils.sdkinit.XUpdateInit
import cn.ppps.forwarder.widget.GuideTipsDialog.Companion.showTips
import cn.ppps.forwarder.workers.LoadAppListWorker
import com.jeremyliao.liveeventbus.LiveEventBus
import com.xuexiang.xhttp2.XHttp
import com.xuexiang.xhttp2.callback.DownloadProgressCallBack
import com.xuexiang.xhttp2.exception.ApiException
import com.xuexiang.xui.XUI.getContext
import com.xuexiang.xui.utils.ResUtils
import com.xuexiang.xui.utils.ThemeUtils
import com.xuexiang.xui.utils.ViewUtils
import com.xuexiang.xui.utils.WidgetUtils
import com.xuexiang.xui.widget.dialog.materialdialog.DialogAction
import com.xuexiang.xui.widget.dialog.materialdialog.GravityEnum
import com.xuexiang.xui.widget.dialog.materialdialog.MaterialDialog
import com.xuexiang.xutil.file.FileUtils
import com.xuexiang.xutil.net.NetworkUtils
import com.yarolegovich.slidingrootnav.SlideGravity
import com.yarolegovich.slidingrootnav.SlidingRootNav
import com.yarolegovich.slidingrootnav.SlidingRootNavBuilder
import com.yarolegovich.slidingrootnav.callback.DragStateListener
import java.io.File

@Suppress("PrivatePropertyName", "unused", "DEPRECATION")
class MainActivity : BaseActivity<ActivityMainBinding?>(), DrawerAdapter.OnItemSelectedListener {

    private val TAG: String = MainActivity::class.java.simpleName

    companion object {
        //打开 App 自动检查更新的最小间隔（进程内有效）：避免反复切回前台时重复下载
        private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 10 * 60 * 1000L
        private var lastAutoUpdateCheck = 0L
    }
    private val POS_LOG = 0
    private val POS_RULE = 1
    private val POS_SENDER = 2
    private val POS_SETTING = 3
    private val POS_TASK = 5 //4为空行
    private val POS_SERVER = 6
    private val POS_CLIENT = 7
    private val POS_FRPC = 8
    private val POS_APPS = 9
    private val POS_HELP = 11 //10为空行
    private val POS_ABOUT = 12
    private var needToAppListFragment = false

    private lateinit var mTabLayout: TabLayout
    private lateinit var mSlidingRootNav: SlidingRootNav
    private lateinit var mLLMenu: LinearLayout
    private lateinit var mMenuTitles: Array<String>
    private lateinit var mMenuIcons: Array<Drawable>
    private lateinit var mAdapter: DrawerAdapter

    override fun viewBindingInflate(inflater: LayoutInflater?): ActivityMainBinding {
        return ActivityMainBinding.inflate(inflater!!)
    }

    /**
     * 通话转发/通话备注可能是通过「配置导入」（含首次打开的内置配置）开启的，没有经过设置页开关，
     * 这里补一次权限申请：监听通话状态/通话记录/联系人（转发还要读手机号）；
     * 通话备注弹窗模式还需要悬浮窗权限，否则会一直退化为通知。
     * 没有这些权限时通话会被静默丢弃，所以每次打开都检查。
     */
    private fun requestCallPermissionsIfNeeded() {
        if (!SettingUtils.enablePhone && !SettingUtils.callNoteEnabled) return
        val permissions = mutableListOf(
            PermissionLists.getReadPhoneStatePermission(),
            PermissionLists.getReadCallLogPermission(),
            PermissionLists.getReadContactsPermission(),
        )
        if (SettingUtils.enablePhone) {
            permissions.add(PermissionLists.getReadPhoneNumbersPermission())
        }
        if (SettingUtils.callNoteEnabled && SettingUtils.callNoteDisplayMode == CALL_NOTE_DISPLAY_POPUP) {
            permissions.add(PermissionLists.getSystemAlertWindowPermission())
        }
        if (XXPermissions.isGrantedPermissions(this, permissions)) return
        XXPermissions.with(this)
            .permissions(permissions)
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    if (deniedList.isNotEmpty()) {
                        val feature = getString(if (SettingUtils.enablePhone) R.string.forward_calls else R.string.call_note)
                        XToastUtils.error(feature + ": " + getString(R.string.toast_denied))
                    }
                }
            })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        initData()
        initViews()
        initSlidingMenu(savedInstanceState)

        //不在最近任务列表中显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && SettingUtils.enableExcludeFromRecents) {
            val am = App.context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            am.let {
                val tasks = it.appTasks
                if (!tasks.isNullOrEmpty()) {
                    tasks[0].setExcludeFromRecents(true)
                }
            }
        }

        //检查通知权限是否获取
        XXPermissions.with(this)
            .permission(PermissionLists.getNotificationServicePermission())
            .permission(PermissionLists.getPostNotificationsPermission())
            .request(object : OnPermissionCallback {
                override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                    //通知权限弹窗结束后再申请通话转发/通话备注所需权限（XXPermissions 不支持并发申请）
                    requestCallPermissionsIfNeeded()
                    val allGranted = deniedList.isEmpty()
                    if (!allGranted) {
                        XToastUtils.error(R.string.tips_notification)
                        return
                    }
                    //启动前台服务
                    if (!ForegroundService.isRunning) {
                        val serviceIntent = Intent(getTopActivity(), ForegroundService::class.java)
                        serviceIntent.action = ACTION_START
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            startForegroundService(serviceIntent)
                        } else {
                            startService(serviceIntent)
                        }
                    }
                }
            })

        //监听已安装App信息列表加载完成事件
        LiveEventBus.get(EVENT_LOAD_APP_LIST, String::class.java).observe(this) {
            if (needToAppListFragment) {
                openNewPage(AppListFragment::class.java)
            }
        }

        //新机首次打开：填写 baseurl 与设备名称，导入内置默认配置
        maybeShowDefaultConfigSetup()
    }

    override fun onStart() {
        super.onStart()
        //每次打开 App 都检查是否是最新版本；有新版本自动下载并调起安装。
        //App 常驻且不在最近任务中显示，onCreate 很少重跑，所以放在 onStart；短时间内重复进入不重复检查
        val now = System.currentTimeMillis()
        if (SettingUtils.autoCheckUpdate && NetworkUtils.isHaveInternet() && now - lastAutoUpdateCheck > AUTO_UPDATE_CHECK_INTERVAL_MS) {
            lastAutoUpdateCheck = now
            XUpdateInit.checkUpdateAuto(this)
        }
    }

    /**
     * 新机首次打开：依次询问 baseurl（默认 [DefaultConfig.DEFAULT_BASE_URL]）和设备名称，然后导入内置默认配置。
     * 不可取消，导入成功才标记完成，中途退出下次打开会再问；已配置过发送通道的（老设备升级）不打扰。
     */
    private fun maybeShowDefaultConfigSetup() {
        try {
            if (DefaultConfig.isApplied(this)) return
            if (Core.sender.getAllNonCache().isNotEmpty()) {
                DefaultConfig.markApplied(this)
                return
            }
            askBaseUrl(DefaultConfig.DEFAULT_BASE_URL)
        } catch (e: Exception) {
            Log.e(TAG, "maybeShowDefaultConfigSetup: ${e.message}")
        }
    }

    private fun askBaseUrl(prefill: String) {
        MaterialDialog.Builder(this)
            .title(R.string.default_config_title)
            .content(R.string.default_config_base_url_content)
            .inputType(InputType.TYPE_TEXT_VARIATION_URI)
            .input(getString(R.string.default_config_base_url_hint), prefill, false) { _: MaterialDialog?, input: CharSequence? ->
                val raw = input?.toString() ?: ""
                val baseUrl = DefaultConfig.normalizeBaseUrl(raw)
                if (baseUrl == null) {
                    XToastUtils.error(R.string.default_config_invalid_url)
                    askBaseUrl(raw)
                } else {
                    askDeviceName(baseUrl)
                }
            }
            .positiveText(R.string.default_config_next)
            .cancelable(false)
            .show()
    }

    private fun askDeviceName(baseUrl: String) {
        MaterialDialog.Builder(this)
            .title(R.string.default_config_title)
            .content(R.string.default_config_device_name_content)
            .inputType(InputType.TYPE_CLASS_TEXT)
            .input(getString(R.string.default_config_device_name_hint), SettingUtils.extraDeviceMark, false) { _: MaterialDialog?, input: CharSequence? ->
                val name = input?.toString()?.trim() ?: ""
                if (name.isEmpty()) askDeviceName(baseUrl) else applyDefaultConfig(baseUrl, name)
            }
            .positiveText(R.string.confirm)
            .cancelable(false)
            .show()
    }

    private fun applyDefaultConfig(baseUrl: String, deviceName: String) {
        XToastUtils.toast(getString(R.string.default_config_applying))
        DefaultConfig.apply(this, baseUrl, deviceName) { ok: Boolean, msg: String? ->
            if (ok) {
                MaterialDialog.Builder(this)
                    .title(R.string.default_config_title)
                    .content(R.string.default_config_done)
                    .cancelable(false)
                    .positiveText(R.string.confirm)
                    .onPositive { _: MaterialDialog?, _: DialogAction? ->
                        //导入的设置（前台服务、定时任务、通话监听等）大多在进程启动时读取，重启整个 App 才完全生效
                        restartApplication()
                    }
                    .show()
            } else {
                XToastUtils.error(getString(R.string.default_config_failed) + (msg ?: ""))
                askBaseUrl(baseUrl)
            }
        }
    }

    override val isSupportSlideBack: Boolean
        get() = false

    private fun initViews() {
        WidgetUtils.clearActivityBackground(this)
        initTab()
    }

    private fun initTab() {
        mTabLayout = binding!!.tabs
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_logs), R.drawable.selector_icon_tabbar_logs)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_rules), R.drawable.selector_icon_tabbar_rules)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_senders), R.drawable.selector_icon_tabbar_senders)
        WidgetUtils.addTabWithoutRipple(mTabLayout, getString(R.string.menu_settings), R.drawable.selector_icon_tabbar_settings)
        WidgetUtils.setTabLayoutTextFont(mTabLayout)
        switchPage(LogsFragment::class.java)
        mTabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                needToAppListFragment = false
                mAdapter.setSelected(tab.position)
                when (tab.position) {
                    POS_LOG -> switchPage(LogsFragment::class.java)
                    POS_RULE -> switchPage(RulesFragment::class.java)
                    POS_SENDER -> switchPage(SendersFragment::class.java)
                    POS_SETTING -> switchPage(SettingsFragment::class.java)
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
    }

    private fun initData() {
        mMenuTitles = ResUtils.getStringArray(this, R.array.menu_titles)
        mMenuIcons = ResUtils.getDrawableArray(this, R.array.menu_icons)

        //仅当开启自动检查且有网络时自动检查更新/获取提示
        if (SettingUtils.autoCheckUpdate && NetworkUtils.isHaveInternet()) {
            showTips(this)
        }
    }

    //按返回键不退出回到桌面
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val intent = Intent(Intent.ACTION_MAIN)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        intent.addCategory(Intent.CATEGORY_HOME)
        startActivity(intent)
    }

    fun openMenu() {
        mSlidingRootNav.openMenu()
    }

    fun closeMenu() {
        mSlidingRootNav.closeMenu()
    }

    fun isMenuOpen(): Boolean {
        return mSlidingRootNav.isMenuOpened
    }

    private fun initSlidingMenu(savedInstanceState: Bundle?) {
        mSlidingRootNav = SlidingRootNavBuilder(this).withGravity(if (ResUtils.isRtl(this)) SlideGravity.RIGHT else SlideGravity.LEFT).withMenuOpened(false).withContentClickableWhenMenuOpened(false).withSavedState(savedInstanceState).withMenuLayout(R.layout.menu_left_drawer).inject()
        mLLMenu = mSlidingRootNav.layout.findViewById(R.id.ll_menu)
        ViewUtils.setVisibility(mLLMenu, false)
        mAdapter = DrawerAdapter(
            mutableListOf(
                createItemFor(POS_LOG).setChecked(true),
                createItemFor(POS_RULE),
                createItemFor(POS_SENDER),
                createItemFor(POS_SETTING),
                SpaceItem(15),
                createItemFor(POS_TASK),
                createItemFor(POS_SERVER),
                createItemFor(POS_CLIENT),
                createItemFor(POS_FRPC),
                createItemFor(POS_APPS),
                SpaceItem(15),
                createItemFor(POS_HELP),
                createItemFor(POS_ABOUT),
            )
        )
        mAdapter.setListener(this)
        val list: RecyclerView = findViewById(R.id.list)
        list.isNestedScrollingEnabled = false
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = mAdapter
        mAdapter.setSelected(POS_LOG)
        mSlidingRootNav.isMenuLocked = false
        mSlidingRootNav.layout.addDragStateListener(object : DragStateListener {
            override fun onDragStart() {
                ViewUtils.setVisibility(mLLMenu, true)
            }

            override fun onDragEnd(isMenuOpened: Boolean) {
                ViewUtils.setVisibility(mLLMenu, isMenuOpened)
            }
        })
    }

    override fun onItemSelected(position: Int) {
        needToAppListFragment = false
        when (position) {
            POS_LOG, POS_RULE, POS_SENDER, POS_SETTING -> {
                val tab = mTabLayout.getTabAt(position)
                tab?.select()
                mSlidingRootNav.closeMenu()
            }

            POS_TASK -> openNewPage(TasksFragment::class.java)
            POS_SERVER -> openNewPage(ServerFragment::class.java)
            POS_CLIENT -> openNewPage(ClientFragment::class.java)
            POS_FRPC -> {
                if (App.FrpclibInited) {
                    openNewPage(FrpcFragment::class.java)
                    return
                }

                val title = if (!FileUtils.isFileExists(filesDir.absolutePath + "/libs/libgojni.so")) {
                    String.format(getString(R.string.frpclib_download_title), FRPC_LIB_VERSION)
                } else {
                    getString(R.string.frpclib_version_mismatch)
                }

                MaterialDialog.Builder(this)
                    .title(title)
                    .content(R.string.download_frpc_tips)
                    .positiveText(R.string.lab_yes)
                    .negativeText(R.string.lab_no)
                    .onPositive { _: MaterialDialog?, _: DialogAction? ->
                        downloadFrpcLib()
                    }
                    .show()
            }

            POS_APPS -> {
                //检查读取应用列表权限是否获取
                XXPermissions.with(this)
                    .permission(PermissionLists.getGetInstalledAppsPermission())
                    .request(object : OnPermissionCallback {
                        override fun onResult(grantedList: MutableList<IPermission>, deniedList: MutableList<IPermission>) {
                            val allGranted = deniedList.isEmpty()
                            if (!allGranted) {
                                // 判断请求失败的权限是否被用户勾选了不再询问的选项
                                val doNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(getTopActivity(), deniedList)
                                if (doNotAskAgain) {
                                    XXPermissions.startPermissionActivity(getContext(), deniedList)
                                }
                                // 处理权限请求失败的逻辑
                                XToastUtils.error(R.string.tips_get_installed_apps)
                                return
                            }
                            // 处理权限请求成功的逻辑
                            if (App.UserAppList.isEmpty() && App.SystemAppList.isEmpty()) {
                                XToastUtils.info(getString(R.string.loading_app_list))
                                val request = OneTimeWorkRequestBuilder<LoadAppListWorker>().build()
                                WorkManager.getInstance(getContext()).enqueue(request)
                                needToAppListFragment = true
                                return
                            }
                            openNewPage(AppListFragment::class.java)
                        }
                    })
            }

            POS_HELP -> AgentWebActivity.goWeb(this, getString(R.string.url_help))
            POS_ABOUT -> openNewPage(AboutFragment::class.java)
        }
    }

    private fun createItemFor(position: Int): DrawerItem<*> {
        return SimpleItem(mMenuIcons[position], mMenuTitles[position])
            .withIconTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withTextTint(ThemeUtils.resolveColor(this, R.attr.xui_config_color_content_text))
            .withSelectedIconTint(ThemeUtils.getMainThemeColor(this))
            .withSelectedTextTint(ThemeUtils.getMainThemeColor(this))
    }

    //动态加载FrpcLib
    private fun downloadFrpcLib() {
        val cpuAbi = when (Build.CPU_ABI) {
            "x86" -> "x86"
            "x86_64" -> "x86_64"
            "arm64-v8a" -> "arm64-v8a"
            else -> "armeabi-v7a"
        }

        val libPath = filesDir.absolutePath + "/libs"
        val soFile = File(libPath)
        if (!soFile.exists()) soFile.mkdirs()
        val downloadUrl = String.format(FRPC_LIB_DOWNLOAD_URL, FRPC_LIB_VERSION, cpuAbi)
        val mContext = this
        val dialog: MaterialDialog = MaterialDialog.Builder(mContext)
            .title(String.format(getString(R.string.frpclib_download_title), FRPC_LIB_VERSION))
            .content(getString(R.string.frpclib_download_content))
            .contentGravity(GravityEnum.CENTER)
            .progress(false, 0, true)
            .progressNumberFormat("%2dMB/%1dMB")
            .build()

        XHttp.downLoad(downloadUrl)
            .ignoreHttpsCert()
            .savePath(cacheDir.absolutePath)
            .execute(object : DownloadProgressCallBack<String?>() {
                override fun onStart() {
                    dialog.show()
                }

                override fun onError(e: ApiException) {
                    dialog.dismiss()
                    XToastUtils.error(e.message.toString())
                }

                override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                    Log.d(TAG, "onProgress: bytesRead=$bytesRead, contentLength=$contentLength")
                    dialog.maxProgress = (contentLength / 1048576L).toInt()
                    dialog.setProgress((bytesRead / 1048576L).toInt())
                }

                override fun onComplete(srcPath: String) {
                    dialog.dismiss()
                    Log.d(TAG, "srcPath = $srcPath")

                    val srcFile = File(srcPath)
                    val destFile = File("$libPath/libgojni.so")
                    FileUtils.moveFile(srcFile, destFile, null)

                    MaterialDialog.Builder(this@MainActivity)
                        .iconRes(R.drawable.ic_menu_frpc)
                        .title(R.string.menu_frpc)
                        .content(R.string.download_frpc_tips2)
                        .cancelable(false)
                        .positiveText(R.string.confirm)
                        .onPositive { _: MaterialDialog?, _: DialogAction? ->
                            restartApplication()
                        }
                        .show()
                }
            })

    }

}
