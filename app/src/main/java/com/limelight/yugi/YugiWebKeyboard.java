package com.limelight.yugi;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONObject;

import java.util.Collections;

/**
 * The YugiPC keyboard for text fields inside a web page (Boosteroid login, search...): the phone's
 * keyboard never shows in the window ({@link YugiFieldKeyboard} sets FLAG_ALT_FOCUSABLE_IM), so a small
 * page script reports when an editable element takes the focus, our keyboard comes up, and keys are
 * applied in the page (insertText, delete, caret moves, Enter submits the form). The page rides up
 * with the keyboard when the field would be covered.
 */
public final class YugiWebKeyboard implements YugiKeyEngine.Sender, YugiFieldKeyboard.InsetListener {
    private static final String JS = "(function(){if(window.__yugiKb)return;"
            + "function ed(e){if(!e)return false;if(e.isContentEditable)return true;var t=e.tagName;"
            + "if(t==='TEXTAREA')return !e.readOnly&&!e.disabled;"
            + "if(t==='INPUT'){var y=(e.type||'text').toLowerCase();return ['text','search','email','url','tel','password','number',''].indexOf(y)>=0&&!e.readOnly&&!e.disabled;}return false;}"
            + "function rep(){try{var e=document.activeElement;if(ed(e)){var r=e.getBoundingClientRect();YugiKbdNative.focus(true,r.bottom/Math.max(1,innerHeight));}else YugiKbdNative.focus(false,0);}catch(x){}}"
            + "document.addEventListener('focusin',function(){setTimeout(rep,0)},true);"
            + "document.addEventListener('focusout',function(){setTimeout(rep,60)},true);"
            + "document.addEventListener('pointerup',function(ev){if(ed(ev.target))setTimeout(rep,0)},true);"
            + "function sel(e,a,b,d){try{e.setSelectionRange(a,b,d);return true}catch(x){return false}}"
            + "window.__yugiKb={"
            + "text:function(s){var e=document.activeElement;if(!ed(e))return;if(!document.execCommand('insertText',false,s)&&e.setRangeText){e.setRangeText(s,e.selectionStart,e.selectionEnd,'end');e.dispatchEvent(new Event('input',{bubbles:true}));}},"
            + "key:function(k,sh,ct){var e=document.activeElement;if(!ed(e))return;try{"
            + "if(ct){if(k==='a'){if(e.select)e.select();else document.execCommand('selectAll');}return;}"
            + "if(k==='Backspace'){document.execCommand('delete');return;}"
            + "if(k==='Delete'){document.execCommand('forwardDelete');return;}"
            + "if(k==='Escape'){e.blur();return;}"
            + "if(k==='Enter'){if(e.tagName==='TEXTAREA'||e.isContentEditable){document.execCommand('insertText',false,'\\n');return;}"
            + "var o={key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true,cancelable:true};var ok=e.dispatchEvent(new KeyboardEvent('keydown',o));e.dispatchEvent(new KeyboardEvent('keypress',o));e.dispatchEvent(new KeyboardEvent('keyup',o));"
            + "if(ok&&e.form){if(e.form.requestSubmit)e.form.requestSubmit();else e.form.submit();}return;}"
            + "var hor=k==='ArrowLeft'||k==='ArrowRight';"
            + "if(e.setSelectionRange&&e.selectionStart!=null&&!e.isContentEditable){var n=e.value.length,s=e.selectionStart,en=e.selectionEnd,bw=e.selectionDirection==='backward';"
            + "if(hor){var rtl=getComputedStyle(e).direction==='rtl';var d=((k==='ArrowRight')!==rtl)?1:-1;"
            + "if(!sh&&s!==en){var p=d>0?en:s;sel(e,p,p);return;}"
            + "var f=bw?s:en,np=Math.max(0,Math.min(n,f+d));if(sh){var an=bw?en:s;if(np<an)sel(e,np,an,'backward');else sel(e,an,np,'forward');}else sel(e,np,np);return;}"
            + "if(e.tagName==='INPUT'){var q=k==='ArrowUp'?0:n;sel(e,sh?Math.min(q,s):q,sh?Math.max(q,en):q);return;}}"
            + "getSelection().modify(sh?'extend':'move',hor?(k==='ArrowRight'?'right':'left'):(k==='ArrowUp'?'backward':'forward'),hor?'character':'line');"
            + "}catch(x){}}};"
            + "rep();})();";

    private final Activity activity;
    private final WebView web;
    private final YugiFieldKeyboard kb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private float fieldBottom = -1;   // focused field bottom, fraction of the page height
    private boolean riding;          // the page is moved by the keyboard right now

    public static YugiWebKeyboard attach(Activity a, WebView web, YugiFieldKeyboard kb) {
        return new YugiWebKeyboard(a, web, kb);
    }

    private YugiWebKeyboard(Activity a, WebView web, YugiFieldKeyboard kb) {
        activity = a;
        this.web = web;
        this.kb = kb;
        kb.addInsetListener(this);
        web.addJavascriptInterface(this, "YugiKbdNative");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            try { WebViewCompat.addDocumentStartJavaScript(web, JS, Collections.singleton("*")); } catch (Exception ignored) {}
        }
        web.evaluateJavascript(JS, null); // a page that is already loaded
    }

    /** Called by the page script (background thread). */
    @JavascriptInterface
    public void focus(boolean on, float bottom) {
        ui.post(() -> {
            if (on) {
                fieldBottom = bottom;
                riding = true;
                if (kb.isExternal()) onKeyboardInset(Math.round(kb.view().pushHeight()));
                kb.showExternal(this, this::hiddenByUser);
            } else if (kb.isExternal()) {
                kb.hide();
            }
        });
    }

    private void hiddenByUser() {
        // the keyboard went down: let the field go too, so the next tap focuses it again
        web.evaluateJavascript("(function(){var e=document.activeElement;if(e&&e!==document.body)e.blur();})()", null);
    }

    // ---------------------------------------------------------------- Sender

    @Override
    public void text(String s) {
        web.evaluateJavascript("window.__yugiKb&&__yugiKb.text(" + JSONObject.quote(s) + ")", null);
    }

    @Override
    public void key(int code, boolean down, int mods) {
        if (!down) return;
        String k;
        switch (code) {
            case KeyEvent.KEYCODE_DEL: k = "Backspace"; break;
            case KeyEvent.KEYCODE_FORWARD_DEL: k = "Delete"; break;
            case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER: k = "Enter"; break;
            case KeyEvent.KEYCODE_DPAD_LEFT: k = "ArrowLeft"; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: k = "ArrowRight"; break;
            case KeyEvent.KEYCODE_DPAD_UP: k = "ArrowUp"; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: k = "ArrowDown"; break;
            case KeyEvent.KEYCODE_ESCAPE: kb.hide(); return;
            case KeyEvent.KEYCODE_A: k = "a"; break;
            default: return; // modifiers, Tab, F-keys: nothing to do in a web field
        }
        boolean shift = (mods & YugiKeyLayout.M_SHIFT) != 0, ctrl = (mods & YugiKeyLayout.M_CTRL) != 0;
        if (k.equals("a") && !ctrl) return;
        web.evaluateJavascript("window.__yugiKb&&__yugiKb.key('" + k + "'," + shift + "," + ctrl + ")", null);
    }

    // ---------------------------------------------------------------- the page rides with the keyboard

    @Override
    public void onKeyboardInset(int px) {
        if (!riding) return;
        if (kb.isShown() && !kb.isExternal()) { riding = false; web.setTranslationY(0); return; } // the chat took it
        int h = web.getHeight();
        if (h <= 0 || fieldBottom < 0) return;
        float covered = fieldBottom * h + YugiTheme.dp(activity, 24) - (h - kb.view().pushHeight());
        float shift = Math.max(0f, Math.min(covered, kb.view().pushHeight()));
        float full = Math.max(1f, kb.view().pushHeight());
        web.setTranslationY(-shift * Math.min(1f, px / full));
        if (px == 0 && !kb.isExternal()) { riding = false; web.setTranslationY(0); }
    }
}
