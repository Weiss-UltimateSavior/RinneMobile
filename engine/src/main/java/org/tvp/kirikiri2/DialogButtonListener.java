package org.tvp.kirikiri2;

import android.content.DialogInterface;

public final class DialogButtonListener implements DialogInterface.OnClickListener {
    public final int buttonIndex;
    public final KrDialogModel dialogModel;
    public DialogButtonListener(KrDialogModel dialogModel, int buttonIndex) {
        this.dialogModel = dialogModel;
        this.buttonIndex = buttonIndex;
    }
    @Override public void onClick(DialogInterface dialog, int which) {
        if (buttonIndex == 0) dialogModel.notifyButtonClicked(0);
        else if (buttonIndex == 1) dialogModel.notifyButtonClicked(1);
        else dialogModel.notifyButtonClicked(2);
    }
}
