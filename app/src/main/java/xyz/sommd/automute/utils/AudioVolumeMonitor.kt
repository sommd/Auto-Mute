/*
 * Copyright (C) 2018 David Sommerich
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package xyz.sommd.automute.utils

import android.content.*
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.SparseIntArray
import androidx.core.content.systemService
import androidx.core.util.getOrDefault
import androidx.core.util.set

/**
 * Class for monitoring the volume level changes of audio streams.
 *
 * Note: this class may not be completely reliable as it relies on the [Settings.System]
 * [ContentProvider], which may not be notified when volume levels are changed. It also won't be
 * notified of streams being muted/unmuted.
 *
 * @param context The [Context] to use.
 * @param streams The audio streams to monitor the volume of, e.g. [AudioManager.STREAM_MUSIC].
 * @param handler The [Handler] for the thread on which to execute the [listener].
 * @param audioManager The [AudioManager] to use.
 * @param audioManager The [ContentResolver] to use.
 */
class AudioVolumeMonitor(
        private val context: Context,
        private val streams: IntArray = ALL_STREAMS,
        private val handler: Handler = Handler(Looper.getMainLooper()),
        private val audioManager: AudioManager = context.systemService(),
        private val contentResolver: ContentResolver = context.contentResolver
) {
    interface Listener {
        /**
         * Called when the volume of [stream] is changed.
         */
        fun onVolumeChange(stream: Int, volume: Int) {}
        
        /**
         * Called when audio is about to become 'noisy' due to a change in audio outputs.
         *
         * @see AudioManager.ACTION_AUDIO_BECOMING_NOISY
         */
        fun onAudioBecomingNoisy()
    }
    
    companion object {
        /** All available audio streams. */
        private val ALL_STREAMS = intArrayOf(
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.STREAM_SYSTEM,
                AudioManager.STREAM_RING,
                AudioManager.STREAM_MUSIC,
                AudioManager.STREAM_ALARM,
                AudioManager.STREAM_NOTIFICATION,
                AudioManager.STREAM_DTMF,
                AudioManager.STREAM_ACCESSIBILITY
        )
        
        /** [IntentFilter] for [receiver]. */
        private val AUDIO_BECOMING_NOISY_INTENT_FILTER =
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        
        /**
         * Amount to delay before updating volume after [AudioManager.ACTION_HEADSET_PLUG]. When
         * updating immediately, volume may not have changed yet.
         */
        private const val HEADSET_PLUG_DELAY_MS = 100L
    }
    
    /** Previous stream volumes to keep track of which volumes changed. */
    private val streamVolumes = SparseIntArray()
    
    /** [Uri]s for volume settings in [Settings.System]. */
    private val volumeUris: List<Uri> = mutableListOf<Uri>().apply {
        contentResolver.query(Settings.System.CONTENT_URI, arrayOf("name"), null, null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(0)
                if ("volume" in name) {
                    add(Settings.System.getUriFor(name))
                }
            }
        }
    }
    
    /** [ContentObserver] for monitoring [Settings.System]. */
    private val contentObserver = object: ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri) {
            this@AudioVolumeMonitor.log { "Volume setting changed: $uri" }
            
            updateVolumes(true)
        }
    }
    
    /** [AudioDeviceCallback] to monitor for [AudioDeviceInfo] changes. */
    private val deviceCallback = object: AudioDeviceCallback() {
        private val updateRunnable = Runnable {
            updateVolumes(true)
        }
        
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            this@AudioVolumeMonitor.log { "Devices added: ${addedDevices.contentToString()}" }
            
            handler.postDelayed(updateRunnable, HEADSET_PLUG_DELAY_MS)
        }
        
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            this@AudioVolumeMonitor.log { "Devices removed: ${removedDevices.contentToString()}" }
            
            handler.postDelayed(updateRunnable, HEADSET_PLUG_DELAY_MS)
        }
    }
    
    /** [BroadcastReceiver] for receiving [AudioManager.ACTION_AUDIO_BECOMING_NOISY]. */
    private val receiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            this@AudioVolumeMonitor.log { "Audio becoming noisy" }
            
            // Notify audio becoming noisy
            listener?.onAudioBecomingNoisy()
        }
    }
    
    /** The [Listener] to be notified of volume changes. */
    var listener: Listener? = null
    
    /**
     * Register this [AudioVolumeMonitor] to start monitor for stream volume changes.
     *
     * @param notifyNow If `true`, the [Listener] will be notified of the current stream volumes.
     * The [Listener] will be called on the [handler] thread.
     */
    fun start(notifyNow: Boolean = false) {
        handler.postOrRunNow {
            // streamVolumes should be empty so this will notify for all stream volumes (if
            // notifyNow is true)
            updateVolumes(notifyNow)
        }
        
        // Register contentObserver for each volumeUri
        log { "Registering observers for $volumeUris" }
        for (uri in volumeUris) {
            contentResolver.registerContentObserver(uri, false, contentObserver)
        }
        
        // Register deviceDallback and receiver on handler thread
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        context.registerReceiver(receiver, AUDIO_BECOMING_NOISY_INTENT_FILTER, null, handler)
    }
    
    /**
     * Stop monitoring stream volume changes.
     */
    fun stop() {
        // Stop listening
        contentResolver.unregisterContentObserver(contentObserver)
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        context.unregisterReceiver(receiver)
        
        // Clear stream volumes
        streamVolumes.clear()
    }
    
    /** Update [streamVolumes] and notify [listener] of any changes if [notify] is `true`. */
    private fun updateVolumes(notify: Boolean) {
        // Get volume of each stream
        for (stream in streams) {
            val volume = audioManager.getStreamVolume(stream)
            
            // Update volume if previous value is different or unknown
            if (volume != streamVolumes.getOrDefault(stream, -1)) {
                streamVolumes[stream] = volume
                
                if (notify) {
                    listener?.onVolumeChange(stream, volume)
                }
            }
        }
    }
}