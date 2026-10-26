package com.example.yiyan;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** 说明书：使用步骤 + XML 导入示例。 */
public class HelpActivity extends ThemedActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);
        ScreenInsets.applyTo(this);
        findViewById(R.id.help_close).setOnClickListener(v -> finish());
    }
}
