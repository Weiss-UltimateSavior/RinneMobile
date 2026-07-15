package org.tvp.kirikiri2;

import android.app.AlertDialog;
import android.widget.EditText;

public final class KrDialogModel {
    public String title;
    public String message;
    public String[] buttons;
    public EditText editText;

    public AlertDialog.Builder createBuilder() {
        AlertDialog.Builder b = new AlertDialog.Builder(KR2Activity.sInstance)
                .setTitle(title).setMessage(message).setCancelable(false);
        String[] arr = buttons != null ? buttons : new String[]{"OK"};
        if (arr.length >= 1) b = b.setPositiveButton(arr[0], new DialogButtonListener(this, 0));
        if (arr.length >= 2) b = b.setNeutralButton(arr[1], new DialogButtonListener(this, 1));
        if (arr.length >= 3) b = b.setNegativeButton(arr[2], new DialogButtonListener(this, 2));
        return b;
    }

    public void notifyButtonClicked(int which) {
        if (editText != null) KR2Activity.onMessageBoxText(editText.getText().toString());
        KR2Activity.onMessageBoxOK(which);
    }
}
