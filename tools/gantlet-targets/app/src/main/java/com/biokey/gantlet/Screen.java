package com.biokey.gantlet;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.widget.TextView;

/**
 * A screen that says which app and which screen it is, in big letters, on a loud background: if
 * one ever shows uncovered, a screenshot says so at a glance. Logs each resume (tag BioKeyGantlet).
 */
public abstract class Screen extends Activity {
    static final String TAG = "BioKeyGantlet";
    final Handler handler = new Handler(Looper.getMainLooper());

    abstract String screenName();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        text.setText("GANTLET\n" + BuildConfig.SCENARIO + "\n" + screenName());
        text.setTextSize(40);
        text.setTextColor(Color.WHITE);
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(Color.rgb(220, 0, 120));
        setContentView(text);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.w(TAG, BuildConfig.SCENARIO + " " + screenName() + " resumed");
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
