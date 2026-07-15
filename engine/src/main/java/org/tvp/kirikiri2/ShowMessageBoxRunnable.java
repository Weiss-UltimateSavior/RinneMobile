package org.tvp.kirikiri2;

public final class ShowMessageBoxRunnable implements Runnable {
    @Override public void run() {
        KR2Activity.mDialogMessage.createBuilder().create().show();
    }
}
