package com.openlib.videolibrary;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.VideoView;

/**
 * Minimal native video player.
 * Uses Android's built-in VideoView (backed by MediaPlayer) and MediaController
 * for play/pause/seek, so playback genuinely runs through Android's media stack
 * rather than handing off to another app.
 */
public class PlayerActivity extends Activity {

    private VideoView videoView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_player);

        String uriString = getIntent().getStringExtra("uri");
        String title = getIntent().getStringExtra("title");

        TextView titleView = findViewById(R.id.playerTitle);
        titleView.setText(title != null ? title : "Video");

        ImageButton closeButton = findViewById(R.id.playerClose);
        closeButton.setOnClickListener(v -> finish());

        videoView = findViewById(R.id.playerVideoView);

        if (uriString == null) {
            finish();
            return;
        }

        Uri uri = Uri.parse(uriString);
        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);
        videoView.setVideoURI(uri);

        videoView.setOnPreparedListener(mp -> {
            videoView.start();
        });

        videoView.setOnErrorListener((mp, what, extra) -> {
            titleView.setText("Couldn't play this video");
            return true;
        });

        videoView.setOnCompletionListener(mp -> finish());
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (videoView != null) {
            videoView.stopPlayback();
        }
    }
}
