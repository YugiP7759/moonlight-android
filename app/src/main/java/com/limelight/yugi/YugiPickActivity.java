package com.limelight.yugi;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Hosts Android's file picker for the Yugi chat. The desktop stream screen can't host it itself
 * (it ends when covered), so the picked file is kept in the chat's shared pending list and the
 * panel reopens with it on the next screen.
 */
public class YugiPickActivity extends Activity {
    private static final int REQ = 7201;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState == null) {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "text/*", "application/*"});
            try {
                startActivityForResult(i, REQ);
            } catch (Exception e) {
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ && resultCode == RESULT_OK && data != null && data.getData() != null) {
            YugiChat.importUri(getApplicationContext(), data.getData(), this::finish);
        } else {
            finish();
        }
    }
}
