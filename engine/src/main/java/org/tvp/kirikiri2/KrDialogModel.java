package org.tvp.kirikiri2;

/**
 * 弹窗数据载体 — 由 ShowMessageBox/ShowInputBox 填充，
 * 由 ShowMessageBoxRunnable/ShowInputBoxRunnable 消费。
 */
public final class KrDialogModel {
    public String title;
    public String message;
    public String[] buttons;
}
