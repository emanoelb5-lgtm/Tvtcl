package com.b5solucoes.tcllight;

import android.app.Activity;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.ContentValues;
import android.media.tv.TvContract;
import android.net.Uri;
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
    private static Uri testChannel;
    private static Uri testWatch;
    private void cleanCatalog() {
        if(testWatch != null) getContentResolver().delete(testWatch,null,null);
        if(testChannel != null) getContentResolver().delete(testChannel,null,null);
        testWatch=null;testChannel=null;
    }
    private void publishCatalog() {
        cleanCatalog();long now=System.currentTimeMillis();
        ContentValues channel=new ContentValues();
        channel.put("input_id",getPackageName()+"/.CatalogInput");channel.put("type","TYPE_OTHER");
        channel.put("service_type","SERVICE_TYPE_AUDIO_VIDEO");channel.put("display_number","105");
        channel.put("display_name","Canal multimídia de teste");channel.put("searchable",1);
        testChannel=getContentResolver().insert(TvContract.Channels.CONTENT_URI,channel);
        long channelId=Long.parseLong(testChannel.getLastPathSegment());
        for(int i=0;i<2;i++) {
            ContentValues program=new ContentValues();program.put("channel_id",channelId);
            program.put("title",i==0 ? "Programa agora de teste" : "Próximo programa de teste");
            program.put("start_time_utc_millis",i==0 ? now-60000L : now+60000L);
            program.put("end_time_utc_millis",i==0 ? now+60000L : now+3600000L);program.put("searchable",1);
            getContentResolver().insert(TvContract.Programs.CONTENT_URI,program);
        }
        ContentValues watch=new ContentValues();watch.put("title","Documentário compartilhado de teste");
        watch.put("type",0);watch.put("watch_next_type",0);watch.put("searchable",1);
        watch.put("last_playback_position_millis",600000L);watch.put("duration_millis",1200000L);
        watch.put("last_engagement_time_utc_millis",now);
        watch.put("intent_uri",new Intent(Intent.ACTION_VIEW,Uri.parse("fixture-media://movie/1")).setPackage(getPackageName()).toUri(Intent.URI_INTENT_SCHEME));
        testWatch=getContentResolver().insert(TvContract.WatchNextPrograms.CONTENT_URI,watch);
    }
    private static void playback(boolean playing) {
        if(session != null) session.setPlaybackState(new PlaybackState.Builder()
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,120000,1f)
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE).build());
    }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        handleIntent();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);setIntent(intent);handleIntent();
    }
    private void handleIntent() {
        if(getIntent().getBooleanExtra("clean_catalog",false)) { cleanCatalog();finish();return; }
        if(getIntent().getBooleanExtra("publish_catalog",false)) {
            TextView status=new TextView(this);
            try { publishCatalog();status.setText("Catálogo multimídia publicado"); }
            catch(Exception error) { status.setText("Falha de catálogo: "+error); }
            setContentView(status);return;
        }
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
