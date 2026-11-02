package app.vela.tv.control

import android.service.notification.NotificationListenerService

/** Exists only so the owner can grant notification access, which is what lets Vela see and control other apps' playback. */
class MediaListener : NotificationListenerService()
