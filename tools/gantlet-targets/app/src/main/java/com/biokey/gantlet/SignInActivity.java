package com.biokey.gantlet;

import android.content.Intent;
import android.os.Bundle;

/** A sign-in check between the launch screen and the main one ("chain"). */
public class SignInActivity extends Screen {

    @Override
    String screenName() {
        return "sign-in";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handler.postDelayed(() -> {
            startActivity(new Intent(this, MainActivity.class));
            finish();
        }, 500);
    }
}
