package com.b5solucoes.tcllight;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

/** Standalone test-package process: intentionally uses only Android SDK classes. */
public class MediaFixtureActivity extends Activity {
    private static MediaSession session;
    private static void playback(boolean playing) {
        if(session != null) session.setPlaybackState(new PlaybackState.Builder()
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,120000,1f)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE).build());
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        if(getIntent().getBooleanExtra("release_media",false)) {
            if(session != null) session.release();session=null;finish();return;
        }
        if(getIntent().getBooleanExtra("start_media",false)) {
            if(session != null) session.release();
            session=new MediaSession(getApplicationContext(),"TCL Light integration fixture");
            session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE,"Filme de teste")
                .putString(MediaMetadata.METADATA_KEY_ARTIST,"Streaming de teste").build());
            session.setCallback(new MediaSession.Callback() {
                @Override public void onPlay() { playback(true); }
                @Override public void onPause() { playback(false); }
            },new Handler(Looper.getMainLooper()));
            session.setSessionActivity(PendingIntent.getActivity(this,17,new Intent(this,MediaFixtureActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            playback(true);session.setActive(true);
        }
        TextView text=new TextView(this);
        text.setText(Intent.ACTION_SEARCH.equals(getIntent().getAction()) ? "Busca: "+getIntent().getStringExtra(SearchManager.QUERY) : "Filme de teste");
        setContentView(text);
    }
}
