package org.cocos2dx.lib;

import android.app.Activity;
import android.content.Context;
import android.opengl.GLSurfaceView;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.inputmethod.InputMethodManager;

public class Cocos2dxGLSurfaceView extends GLSurfaceView {
    private static final int HANDLER_OPEN_IME_KEYBOARD = 2;
    private static final int HANDLER_CLOSE_IME_KEYBOARD = 3;
    private static final String TAG = "Cocos2dxGLSurfaceView";

    private static Cocos2dxGLSurfaceView mCocos2dxGLSurfaceView;
    private static Cocos2dxTextInputWraper sCocos2dxTextInputWraper;
    private static Handler sHandler;

    private Cocos2dxEditBox mCocos2dxEditText;
    private Cocos2dxRenderer mCocos2dxRenderer;
    private boolean mSoftKeyboardShown;

    public Cocos2dxGLSurfaceView(Context context) {
        super(context);
        this.mSoftKeyboardShown = false;
        initView();
    }

    public Cocos2dxGLSurfaceView(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
        this.mSoftKeyboardShown = false;
        initView();
    }

    public static Cocos2dxGLSurfaceView getInstance() {
        return mCocos2dxGLSurfaceView;
    }

    public static void closeIMEKeyboard() {
        Message message = new Message();
        message.what = HANDLER_CLOSE_IME_KEYBOARD;
        if (sHandler != null) sHandler.sendMessage(message);
    }

    public static void openIMEKeyboard() {
        Message message = new Message();
        message.what = HANDLER_OPEN_IME_KEYBOARD;
        message.obj = mCocos2dxGLSurfaceView != null ? mCocos2dxGLSurfaceView.getContentText() : "";
        if (sHandler != null) sHandler.sendMessage(message);
    }

    public static void queueAccelerometer(final float x, final float y, final float z, final long timestamp) {
        if (mCocos2dxGLSurfaceView == null) return;
        mCocos2dxGLSurfaceView.queueEvent(new Runnable() {
            @Override public void run() { Cocos2dxAccelerometer.onSensorChanged(x, y, z, timestamp); }
        });
    }

    private static void dumpMotionEvent(MotionEvent event) {
        StringBuilder sb = new StringBuilder("event ACTION_");
        int action = event.getAction();
        int actionCode = action & 255;
        String[] names = new String[]{"DOWN", "UP", "MOVE", "CANCEL", "OUTSIDE", "POINTER_DOWN", "POINTER_UP", "7?", "8?", "9?"};
        sb.append(actionCode < names.length ? names[actionCode] : actionCode);
        if (actionCode == 5 || actionCode == 6) sb.append("(pid ").append(action >> 8).append(")");
        sb.append("[");
        for (int i = 0; i < event.getPointerCount(); i++) {
            sb.append("#").append(i).append("(pid ").append(event.getPointerId(i)).append(")=")
                    .append((int) event.getX(i)).append(",").append((int) event.getY(i));
            if (i + 1 < event.getPointerCount()) sb.append(";");
        }
        sb.append("]");
        Log.d(TAG, sb.toString());
    }

    private String getContentText() {
        return this.mCocos2dxRenderer != null ? this.mCocos2dxRenderer.getContentText() : "";
    }

    public void initView() {
        setEGLContextClientVersion(2);
        setFocusableInTouchMode(true);
        mCocos2dxGLSurfaceView = this;
        sCocos2dxTextInputWraper = new Cocos2dxTextInputWraper(this);
        sHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                int what = message.what;
                if (what == HANDLER_OPEN_IME_KEYBOARD) {
                    if (Cocos2dxGLSurfaceView.this.mCocos2dxEditText == null || !Cocos2dxGLSurfaceView.this.mCocos2dxEditText.requestFocus()) return;
                    Cocos2dxGLSurfaceView.this.mCocos2dxEditText.removeTextChangedListener(Cocos2dxGLSurfaceView.sCocos2dxTextInputWraper);
                    Cocos2dxGLSurfaceView.this.mCocos2dxEditText.setText("");
                    String text = (String) message.obj;
                    Cocos2dxGLSurfaceView.this.mCocos2dxEditText.append(text);
                    Cocos2dxGLSurfaceView.sCocos2dxTextInputWraper.setOriginText(text);
                    Cocos2dxGLSurfaceView.this.mCocos2dxEditText.addTextChangedListener(Cocos2dxGLSurfaceView.sCocos2dxTextInputWraper);
                    ((InputMethodManager) Cocos2dxGLSurfaceView.mCocos2dxGLSurfaceView.getContext().getSystemService("input_method"))
                            .showSoftInput(Cocos2dxGLSurfaceView.this.mCocos2dxEditText, 0);
                    Log.d("GLSurfaceView", "showSoftInput");
                } else if (what == HANDLER_CLOSE_IME_KEYBOARD && Cocos2dxGLSurfaceView.this.mCocos2dxEditText != null) {
                    Cocos2dxGLSurfaceView.this.mCocos2dxEditText.removeTextChangedListener(Cocos2dxGLSurfaceView.sCocos2dxTextInputWraper);
                    ((InputMethodManager) Cocos2dxGLSurfaceView.mCocos2dxGLSurfaceView.getContext().getSystemService("input_method"))
                            .hideSoftInputFromWindow(Cocos2dxGLSurfaceView.this.mCocos2dxEditText.getWindowToken(), 0);
                    Cocos2dxGLSurfaceView.this.requestFocus();
                    Log.d("GLSurfaceView", "HideSoftInput");
                }
            }
        };
    }

    public Cocos2dxEditBox getCocos2dxEditText() {
        return this.mCocos2dxEditText;
    }

    public void setCocos2dxEditText(Cocos2dxEditBox editText) {
        this.mCocos2dxEditText = editText;
        if (editText != null && sCocos2dxTextInputWraper != null) {
            editText.setOnEditorActionListener(sCocos2dxTextInputWraper);
        }
        requestFocus();
    }

    public void setCocos2dxRenderer(Cocos2dxRenderer renderer) {
        this.mCocos2dxRenderer = renderer;
        setRenderer(renderer);
    }

    public void setSoftKeyboardShown(boolean shown) {
        this.mSoftKeyboardShown = shown;
    }

    public boolean isSoftKeyboardShown() {
        return this.mSoftKeyboardShown;
    }

    public void insertText(final String text) {
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleInsertText(text); }
        });
    }

    public void deleteBackward() {
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleDeleteBackward(); }
        });
    }

    @Override
    public boolean onKeyDown(final int keyCode, KeyEvent keyEvent) {
        if (keyCode == 4) {
            if (Cocos2dxVideoHelper.mVideoHandler != null) Cocos2dxVideoHelper.mVideoHandler.sendEmptyMessage(1000);
        } else if (keyCode != 66 && keyCode != 82 && keyCode != 85) {
            switch (keyCode) {
                case 19:
                case 20:
                case 21:
                case 22:
                case 23:
                    break;
                default:
                    return super.onKeyDown(keyCode, keyEvent);
            }
        }
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleKeyDown(keyCode); }
        });
        return true;
    }

    @Override
    public boolean onKeyUp(final int keyCode, KeyEvent keyEvent) {
        if (keyCode != 4 && keyCode != 66 && keyCode != 82 && keyCode != 85) {
            switch (keyCode) {
                case 19:
                case 20:
                case 21:
                case 22:
                case 23:
                    break;
                default:
                    return super.onKeyUp(keyCode, keyEvent);
            }
        }
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleKeyUp(keyCode); }
        });
        return true;
    }

    @Override
    public void onPause() {
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleOnPause(); }
        });
        setRenderMode(0);
    }

    @Override
    public void onResume() {
        super.onResume();
        setRenderMode(1);
        queueEvent(new Runnable() {
            @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleOnResume(); }
        });
    }

    @Override
    public void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        if (isInEditMode()) return;
        if (this.mCocos2dxRenderer != null) this.mCocos2dxRenderer.setScreenWidthAndHeight(width, height);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        final int[] ids = new int[pointerCount];
        final float[] xs = new float[pointerCount];
        final float[] ys = new float[pointerCount];
        if (this.mSoftKeyboardShown) {
            try {
                ((InputMethodManager) getContext().getSystemService("input_method"))
                        .hideSoftInputFromWindow(((Activity) getContext()).getCurrentFocus().getWindowToken(), 0);
            } catch (Throwable ignored) { }
            requestFocus();
            this.mSoftKeyboardShown = false;
        }
        for (int i = 0; i < pointerCount; i++) {
            ids[i] = event.getPointerId(i);
            xs[i] = event.getX(i);
            ys[i] = event.getY(i);
        }
        int action = event.getAction() & 255;
        if (action == 0) {
            final int id = event.getPointerId(0);
            final float x = xs[0];
            final float y = ys[0];
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionDown(id, x, y); } });
        } else if (action == 1) {
            final int id = event.getPointerId(0);
            final float x = xs[0];
            final float y = ys[0];
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionUp(id, x, y); } });
        } else if (action == 2) {
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionMove(ids, xs, ys); } });
        } else if (action == 3) {
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionCancel(ids, xs, ys); } });
        } else if (action == 5) {
            int index = event.getAction() >> 8;
            final int id = event.getPointerId(index);
            final float x = event.getX(index);
            final float y = event.getY(index);
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionDown(id, x, y); } });
        } else if (action == 6) {
            int index = event.getAction() >> 8;
            final int id = event.getPointerId(index);
            final float x = event.getX(index);
            final float y = event.getY(index);
            queueEvent(new Runnable() { @Override public void run() { if (mCocos2dxRenderer != null) mCocos2dxRenderer.handleActionUp(id, x, y); } });
        }
        return true;
    }
}