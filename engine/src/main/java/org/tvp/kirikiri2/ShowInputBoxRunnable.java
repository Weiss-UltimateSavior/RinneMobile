package org.tvp.kirikiri2;

import android.app.AlertDialog;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.cocos2dx.lib.Cocos2dxActivity;

public final class ShowInputBoxRunnable implements Runnable {
    public final String inputText;
    public ShowInputBoxRunnable(String inputText) { this.inputText = inputText; }
    @Override public void run() {
        KrDialogModel dialogModel = KR2Activity.mDialogMessage;
        AlertDialog.Builder builder = dialogModel.createBuilder();
        dialogModel.editText = new EditText(KR2Activity.sInstance);
        dialogModel.editText.setLayoutParams(new LinearLayout.LayoutParams(-1, -1));
        dialogModel.editText.setText(inputText);
        builder.setView(dialogModel.editText);
        builder.create().show();
        dialogModel.editText.requestFocus();
        ((InputMethodManager) Cocos2dxActivity.getContext().getSystemService("input_method")).showSoftInput(dialogModel.editText, 0);
    }
}
