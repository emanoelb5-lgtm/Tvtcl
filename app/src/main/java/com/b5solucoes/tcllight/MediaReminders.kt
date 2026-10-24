package com.b5solucoes.tcllight

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

internal data class MediaReminder(val id: String = UUID.randomUUID().toString(), val entry: MediaEntry,
    val startsAt: Long, val fired: Boolean = false) {
    fun json()=JSONObject().put("id",id).put("entry",entry.json()).put("starts_at",startsAt).put("fired",fired)
}

/** Only explicitly created reminders schedule alarms. No polling service or automatic channel switching. */
internal object MediaReminders {
    const val ACTION_OPEN = "com.b5solucoes.tcllight.OPEN_REMINDER"
    const val ACTION_FIRE = "com.b5solucoes.tcllight.FIRE_REMINDER"
    const val CHANNEL = "program-reminders"
    private fun prefs(context: Context)=context.getSharedPreferences("media-reminders",Context.MODE_PRIVATE)
    fun parse(raw: String): List<MediaReminder> {
        require(raw.toByteArray().size <= 96*1024)
        val array=JSONArray(raw);require(array.length() <= 32)
        return (0 until array.length()).map {
            val obj=array.getJSONObject(it);val id=obj.get("id");val at=obj.get("starts_at");val fired=obj.get("fired")
            require(id is String && UUID.fromString(id).toString() == id && (at is Long || at is Int) && fired is Boolean)
            val starts=(at as Number).toLong();require(starts > 0 && starts <= 4102444800000L)
            MediaReminder(id,MediaLibrary.parseEntry(obj.getJSONObject("entry")),starts,fired)
        }.also { require(it.map { r->r.id }.distinct().size == it.size) }
    }
    @Synchronized fun read(context: Context): List<MediaReminder> = runCatching { parse(prefs(context).getString("items","[]")!!) }.getOrDefault(emptyList())
    @Synchronized private fun write(context: Context, entries: List<MediaReminder>) {
        val raw=JSONArray().apply { entries.forEach { put(it.json()) } }.toString();parse(raw)
        check(prefs(context).edit().putString("items",raw).commit())
    }
    fun triggerAt(startsAt: Long, now: Long)=maxOf(now+1000L,startsAt-5*60*1000L)
    @Synchronized fun add(context: Context, entry: MediaEntry, at: Long, now: Long = System.currentTimeMillis()): MediaReminder {
        require(at > now && at <= now+366*24*60*60*1000L) { "Escolha um horário futuro, dentro de um ano." }
        MediaLibrary.parseEntry(entry.json())
        val old=read(context);require(old.size < 32) { "Remova um lembrete para criar outro." }
        val reminder=MediaReminder(entry=entry,startsAt=at)
        write(context,old+reminder)
        try { schedule(context,reminder,now) } catch(error:Exception) { write(context,old);throw error }
        return reminder
    }
    private fun pending(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(context,0,
        Intent(context,ProgramReminderReceiver::class.java).setAction(ACTION_FIRE).setData(Uri.parse("tcllight://reminder/$id")),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private fun schedule(context: Context, reminder: MediaReminder, now: Long) {
        if(reminder.fired || reminder.startsAt <= now) return
        val manager=context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val time=triggerAt(reminder.startsAt,now)
        val intent=pending(context,reminder.id)
        if(Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            try { manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,intent);return } catch(_:SecurityException) { }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,intent)
    }
    @Synchronized fun remove(context: Context, id: String) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pending(context,id))
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id,0)
        write(context,read(context).filterNot { it.id == id })
    }
    @Synchronized fun reschedule(context: Context) {
        val now=System.currentTimeMillis()
        read(context).filter { !it.fired && it.startsAt > now }.forEach { runCatching { schedule(context,it,now) } }
    }
    @Synchronized fun deliver(context: Context, id: String): Boolean {
        val entries=read(context);val reminder=entries.firstOrNull { it.id == id && !it.fired } ?: return false
        // Ignore stale or unexpectedly early deliveries; the next boot/time-change reschedules future entries.
        val now=System.currentTimeMillis()
        if(reminder.startsAt < now-15*60*1000L || reminder.startsAt > now+5*60*1000L+5000L) return false
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL,"Lembretes de programas",NotificationManager.IMPORTANCE_HIGH))
        val open=PendingIntent.getActivity(context,0,Intent(context,MainActivity::class.java).setAction(ACTION_OPEN)
            .setData(Uri.parse("tcllight://reminder/$id")).putExtra("reminder_id",id),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val builder=if(Build.VERSION.SDK_INT >= 26) Notification.Builder(context,CHANNEL) else Notification.Builder(context)
        val text="Começa às "+SimpleDateFormat("HH:mm",Locale("pt","BR")).format(Date(reminder.startsAt))+" · Abrir opções"
        @Suppress("DEPRECATION")
        val notification=builder.setSmallIcon(R.drawable.ic_launcher).setContentTitle(reminder.entry.title).setContentText(text)
            .setCategory(Notification.CATEGORY_EVENT).setPriority(Notification.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(open).extend(Notification.TvExtender().setContentIntent(open)).build()
        if(Build.VERSION.SDK_INT < 33 || context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED)
            try { manager.notify(id,0,notification) } catch(_:SecurityException) { }
        write(context,entries.map { if(it.id == id) it.copy(fired=true) else it })
        return true
    }
}

class ProgramReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending=goAsync()
        Thread {
            try {
                if(intent.action == MediaReminders.ACTION_FIRE) {
                    val id=intent.data?.takeIf { it.scheme == "tcllight" && it.host == "reminder" }?.lastPathSegment
                    if(id != null) MediaReminders.deliver(context,id)
                } else if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_MY_PACKAGE_REPLACED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED))
                    MediaReminders.reschedule(context)
            } catch(_:Exception) { /* A full disk or vendor alarm restriction must not crash Home. */ }
            finally { pending?.finish() }
        }.start()
    }
}
