package com.biokey.gantlet;

import android.Manifest;
import android.content.Intent;
import android.os.Bundle;

/** The app's launch screen: does what its flavor's scenario says (see app/build.gradle.kts). */
public class LaunchActivity extends Screen {

    @Override
    String screenName() {
        return "launch screen";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        switch (BuildConfig.SCENARIO) {
            case "plain":
                open(MainActivity.class, 0, false);
                break;
            case "splash":
                open(MainActivity.class, 300, false);
                break;
            case "late":
                open(MainActivity.class, 1500, false);
                break;
            case "chain":
                open(SignInActivity.class, 500, false);
                break;
            case "newtask":
                open(OwnTaskActivity.class, 500, true);
                break;
            case "permission":
                requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
                break;
            case "loop":
                reopenForEver();
                break;
        }
    }

    private void open(Class<?> screen, long afterMs, boolean ownTask) {
        handler.postDelayed(() -> {
            Intent intent = new Intent(this, screen);
            if (ownTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            finish();
        }, afterMs);
    }

    /** Puts itself in front again every 700 ms, wherever the user went. */
    private void reopenForEver() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(LaunchActivity.this, LaunchActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                handler.postDelayed(this, 700);
            }
        }, 700);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        open(MainActivity.class, 0, false);
    }
}
