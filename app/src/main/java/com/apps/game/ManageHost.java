package com.apps.game;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.core.util.RxMainQueue;

/**
 * LauncherManageFragment 与各 Controller 之间的桥接接口。
 *
 * Fragment 实现此接口，提供生命周期守卫、Context、UI 缩放、共享对话框构建器
 * 及 importInProgress 共享标志，各 Controller 仅依赖此接口而非 Fragment 本身。
 */
public interface ManageHost {

    /** UI 线程使用的 Context（即 Fragment.requireContext()）。 */
    Context requireContext();

    /** 后台线程使用的应用级 Context（即 requireContext().getApplicationContext()）。 */
    Context getAppContext();

    /** Fragment 是否仍附加。 */
    boolean isAdded();

    /** 主线程消息队列。 */
    RxMainQueue getMainQueue();

    /** 启动 Activity。 */
    void startActivity(Intent intent);

    /** 应用级 SharedPreferences。 */
    SharedPreferences getPrefs();

    // ===== UI 缩放 =====

    /** 带平板竖屏缩放的 dp → px 转换。 */
    int dp(int value);

    /** 带平板竖屏缩放的文字大小设置。 */
    void setResponsiveTextSize(TextView view, float baseSp);

    // ===== 共享对话框构建器 =====

    /** 显示确认对话框（复用 LauncherDialogFactory.showConfirm）。 */
    void showConfirmDialog(String title, String message, String confirmText, Runnable onConfirm);

    /** 创建对话框标题 TextView。 */
    TextView createDialogTitle(String text);

    /** 创建对话框取消按钮 TextView。 */
    TextView createDialogCancelButton(AlertDialog dialog);

    /** 向 LinearLayout 添加一个选项行。 */
    void addFeedbackOption(LinearLayout root, String label, AlertDialog dialog, Runnable action);

    /** 创建带进度条的加载对话框（调用方持有生命周期）。 */
    AlertDialog showScanLoadingDialog(String title, String hint);

    // ===== 共享状态 =====

    /** 跨端/本地导入防重复标志。 */
    boolean isImportInProgress();

    void setImportInProgress(boolean inProgress);
}
