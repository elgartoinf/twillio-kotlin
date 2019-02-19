package com.twilio.video.examples.videoinvite

import android.Manifest
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v4.content.LocalBroadcastManager
import android.support.v7.app.AppCompatActivity
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.twilio.video.*
import com.twilio.video.CameraCapturer.CameraSource
import com.twillioexample.gabriel.twillioandroid.R


/*
 * This Activity shows how to use Twilio Video with Twilio Notify to invite other participants
 * that have registered with Twilio Notify via push notifications.
 */
class MainActivity : AppCompatActivity() {

    /*
     * Token obtained from the sdk-starter /token resource
     */
    private var token: String? = null

    /*
     * A Room represents communication between a local participant and one or more participants.
     */
    private var room: Room? = null

    /*
     * A LocalParticipant represents the identity and tracks provided by this instance
     */
    private var localParticipant: LocalParticipant? = null

    /*
     * A VideoView receives frames from a local or remote video track and renders them
     * to an associated view.
     */
    private var primaryVideoView: VideoView? = null
    private var thumbnailVideoView: VideoView? = null

    private var isReceiverRegistered: Boolean = false
    private var localBroadcastReceiver: LocalBroadcastReceiver? = null
    private var notificationManager: NotificationManager? = null
    private var cachedVideoNotificationIntent: Intent? = null

    /*
     * Android application UI elements
     */
    private var statusTextView: TextView? = null
    private var identityTextView: TextView? = null
    private var cameraCapturer: CameraCapturer? = null
    private var localAudioTrack: LocalAudioTrack? = null
    private var localVideoTrack: LocalVideoTrack? = null
    private var audioManager: AudioManager? = null
    private var remoteParticipantIdentity: String? = null

    private var previousAudioMode: Int = 0
    private var localVideoView: VideoRenderer? = null
    private var disconnectedFromOnDestroy: Boolean = false

    private val availableCameraSource: CameraSource
        get() = if (CameraCapturer.isSourceAvailable(CameraSource.FRONT_CAMERA))
            CameraSource.FRONT_CAMERA
        else
            CameraSource.BACK_CAMERA

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        primaryVideoView = findViewById<View>(R.id.primary_video_view) as VideoView
        thumbnailVideoView = findViewById<View>(R.id.thumbnail_video_view) as VideoView
        statusTextView = findViewById<View>(R.id.status_textview) as TextView
        identityTextView = findViewById<View>(R.id.identity_textview) as TextView
        /*
         * Enable changing the volume using the up/down keys during a conversation
         */
        volumeControlStream = AudioManager.STREAM_VOICE_CALL

        /*
         * Needed for setting/abandoning audio focus during call
         */
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager!!.isSpeakerphoneOn = true

        /*
         * Setup the broadcast receiver to be notified of video notification messages
         */
        localBroadcastReceiver = LocalBroadcastReceiver()
        registerReceiver()

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = intent

        /*
         * Check camera and microphone permissions. Needed in Android M.
         */
        if (!checkPermissionForCameraAndMicrophone()) {
            requestPermissionForCameraAndMicrophone()
        } else if (intent != null && intent.action === ACTION_REGISTRATION) {
            handleRegistration(intent)
        }

        createLocalTracks()
        token =
            "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiIsImN0eSI6InR3aWxpby1mcGE7dj0xIn0.eyJqdGkiOiJTSzJjYTMyNTUzOGRhMGQ3ZjdlZjUzNTMxNDFlMWRkNzQzLTE1NTA2MDU5MDEiLCJpc3MiOiJTSzJjYTMyNTUzOGRhMGQ3ZjdlZjUzNTMxNDFlMWRkNzQzIiwic3ViIjoiQUMwOTIzMGQ1MWM4NjBjZjdlOTYyYzFjM2M0MTFhZjIwNyIsImV4cCI6MTU1MDYwOTUwMSwiZ3JhbnRzIjp7ImlkZW50aXR5IjoibmFkYSIsInZpZGVvIjp7InJvb20iOiJjaGFubmVsIn19fQ.C1eaysaQ-vkkzrlM6BwX-qK32uS9nuBCx3lPW7n8O9g"
        if (cachedVideoNotificationIntent != null) {
            handleVideoNotificationIntent(cachedVideoNotificationIntent!!)
            cachedVideoNotificationIntent = null
        }
        connectToRoom("channel")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        if (requestCode == CAMERA_MIC_PERMISSION_REQUEST_CODE) {
            var cameraAndMicPermissionGranted = true

            for (grantResult in grantResults) {
                cameraAndMicPermissionGranted =
                    cameraAndMicPermissionGranted and (grantResult == PackageManager.PERMISSION_GRANTED)
            }

            if (cameraAndMicPermissionGranted) {
                connectToRoom("channel")
            } else {
                Toast.makeText(
                    this,
                   "Este permiso es necesario",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /*
     * Called when a notification is clicked and this activity is in the background or closed
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action === ACTION_VIDEO_NOTIFICATION) {
            handleVideoNotificationIntent(intent)
        }
    }

    private fun handleRegistration(intent: Intent) {
        val registrationError = intent.getStringExtra(REGISTRATION_ERROR)
        if (registrationError != null) {
            statusTextView!!.text = registrationError
        } else {
            createLocalTracks()
            statusTextView!!.text = "Registered"
            if (cachedVideoNotificationIntent != null) {
                handleVideoNotificationIntent(cachedVideoNotificationIntent!!)
                cachedVideoNotificationIntent = null
            }
        }
    }

    private fun handleVideoNotificationIntent(intent: Intent) {
        notificationManager!!.cancelAll()
    }

    private fun registerReceiver() {
        if (!isReceiverRegistered) {
            val intentFilter = IntentFilter()
            intentFilter.addAction(ACTION_VIDEO_NOTIFICATION)
            intentFilter.addAction(ACTION_REGISTRATION)
            LocalBroadcastManager.getInstance(this).registerReceiver(
                localBroadcastReceiver!!, intentFilter
            )
            isReceiverRegistered = true
        }
    }

    private fun unregisterReceiver() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(localBroadcastReceiver!!)
        isReceiverRegistered = false
    }

    private inner class LocalBroadcastReceiver : BroadcastReceiver() {

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == ACTION_REGISTRATION) {
                handleRegistration(intent)
            } else if (action == ACTION_VIDEO_NOTIFICATION) {
                handleVideoNotificationIntent(intent)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        registerReceiver()
        /*
         * If the local video track was released when the app was put in the background, recreate.
         */
        if (localVideoTrack == null &&
            checkPermissionForCameraAndMicrophone() &&
            cameraCapturer != null
        ) {
            localVideoTrack = LocalVideoTrack.create(this, true, cameraCapturer!!)
            localVideoTrack!!.addRenderer(localVideoView!!)


            /*
             * If connected to a Room then share the local video track.
             */
            if (localParticipant != null) {
                localParticipant!!.publishTrack(localVideoTrack!!)
            }
        }
    }

    override fun onPause() {
        unregisterReceiver()
        /*
         * Release the local video track before going in the background. This ensures that the
         * camera can be used by other applications while this app is in the background.
         *
         * If this local video track is being shared in a Room, participants will be notified
         * that the track has been unpublished.
         */
        if (localVideoTrack != null) {
            /*
             * If this local video track is being shared in a Room, unpublish from room before
             * releasing the video track. Participants will be notified that the track has been
             * removed.
             */
            if (localParticipant != null) {
                localParticipant!!.unpublishTrack(localVideoTrack!!)
            }
            localVideoTrack!!.release()
            localVideoTrack = null
        }
        super.onPause()
    }

    override fun onDestroy() {
        /*
         * Always disconnect from the room before leaving the Activity to
         * ensure any memory allocated to the Room resource is freed.
         */
        if (room != null && room!!.state != Room.State.DISCONNECTED) {
            room!!.disconnect()
            disconnectedFromOnDestroy = true
        }

        /*
         * Release the local audio and video tracks ensuring any memory allocated to audio
         * or video is freed.
         */
        if (localAudioTrack != null) {
            localAudioTrack!!.release()
            localAudioTrack = null
        }
        if (localVideoTrack != null) {
            localVideoTrack!!.release()
            localVideoTrack = null
        }

        super.onDestroy()
    }

    private fun checkPermissionForCameraAndMicrophone(): Boolean {
        val resultCamera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return resultCamera == PackageManager.PERMISSION_GRANTED && resultMic == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissionForCameraAndMicrophone() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.CAMERA
            ) || ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.RECORD_AUDIO
            )
        ) {
            Toast.makeText(
                this,
                "Este permiso es necesario",
                Toast.LENGTH_LONG
            ).show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO),
                CAMERA_MIC_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun createLocalTracks() {
        // Share your microphone
        localAudioTrack = LocalAudioTrack.create(this, true)

        // Share your camera
        cameraCapturer = CameraCapturer(this, availableCameraSource)
        localVideoTrack = LocalVideoTrack.create(this, true, cameraCapturer!!)
        primaryVideoView!!.mirror = true
        localVideoTrack!!.addRenderer(primaryVideoView!!)
        localVideoView = primaryVideoView
    }

    private fun connectToRoom(roomName: String) {
        enableAudioFocus(true)
        enableVolumeControl(true)

        val connectOptionsBuilder = ConnectOptions.Builder(token!!)
            .roomName(roomName)

        /*
         * Add local audio track to connect options to share with participants.
         */

        localAudioTrack?.let {
            connectOptionsBuilder
                .audioTracks(listOf<LocalAudioTrack>(it))
        }

        /*
         * Add local video track to connect options to share with participants.
         */
        if (localVideoTrack != null) {
            connectOptionsBuilder.videoTracks(listOf<LocalVideoTrack>(localVideoTrack!!))
        }

        room = Video.connect(this, connectOptionsBuilder.build(), roomListener())
    }

    /*
     * Called when remote participant joins the room
     */
    private fun addRemoteParticipant(remoteParticipant: RemoteParticipant) {
        /*
         * This app only displays video for one additional participant per Room
         */
        if (thumbnailVideoView!!.visibility == View.VISIBLE) {
            Toast.makeText(baseContext,"Rendering multiple participants not supported in this app", Toast.LENGTH_LONG).show()
            return
        }
        remoteParticipantIdentity = remoteParticipant.identity
        statusTextView!!.text = "RemoteParticipant $remoteParticipantIdentity joined"

        /*
         * Add remote participant renderer
         */
        if (remoteParticipant.remoteVideoTracks.size > 0) {
            val remoteVideoTrackPublication = remoteParticipant.remoteVideoTracks[0]

            /*
             * Only render video tracks that are subscribed to
             */
            if (remoteVideoTrackPublication.isTrackSubscribed) {
                addRemoteParticipantVideo(remoteVideoTrackPublication.remoteVideoTrack!!)
            }
        }

        /*
         * Start listening for participant media events
         */
        remoteParticipant.setListener(mediaListener())
    }

    /*
     * Set primary view as renderer for participant video track
     */
    private fun addRemoteParticipantVideo(videoTrack: VideoTrack) {
        moveLocalVideoToThumbnailView()
        primaryVideoView!!.mirror = false
        videoTrack.addRenderer(primaryVideoView!!)
    }

    private fun moveLocalVideoToThumbnailView() {
        if (thumbnailVideoView!!.visibility == View.GONE) {
            thumbnailVideoView!!.visibility = View.VISIBLE
            if (localVideoTrack != null) {
                localVideoTrack!!.removeRenderer(primaryVideoView!!)
                localVideoTrack!!.addRenderer(thumbnailVideoView!!)
            }
            localVideoView = thumbnailVideoView
            thumbnailVideoView!!.mirror = cameraCapturer!!.cameraSource == CameraSource.FRONT_CAMERA
        }
    }

    /*
     * Called when participant leaves the room
     */
    private fun removeParticipant(remoteParticipant: RemoteParticipant) {
        statusTextView!!.text = "Participant " + remoteParticipant.identity + " left."
        if (remoteParticipant.identity != remoteParticipantIdentity) {
            return
        }

        /*
         * Remove participant renderer
         */
        if (remoteParticipant.remoteVideoTracks.size > 0) {
            val remoteVideoTrackPublication = remoteParticipant.remoteVideoTracks[0]

            /*
             * Remove video only if subscribed to participant track.
             */
            if (remoteVideoTrackPublication.isTrackSubscribed) {
                removeParticipantVideo(remoteVideoTrackPublication.remoteVideoTrack!!)
            }
        }
        moveLocalVideoToPrimaryView()
    }

    private fun removeParticipantVideo(videoTrack: VideoTrack) {
        videoTrack.removeRenderer(primaryVideoView!!)
    }

    private fun moveLocalVideoToPrimaryView() {
        if (thumbnailVideoView!!.visibility == View.VISIBLE) {
            localVideoTrack!!.removeRenderer(thumbnailVideoView!!)
            thumbnailVideoView!!.visibility = View.GONE
            localVideoTrack!!.addRenderer(primaryVideoView!!)
            localVideoView = primaryVideoView
            primaryVideoView!!.mirror = cameraCapturer!!.cameraSource == CameraSource.FRONT_CAMERA
        }
    }

    /*
     * Room events listener
     */
    private fun roomListener(): Room.Listener {
        return object : Room.Listener {
            override fun onConnected(room: Room) {
                localParticipant = room.localParticipant
                statusTextView!!.text = "Connected to " + room.name
                title = room.name

                for (remoteParticipant in room.remoteParticipants) {
                    addRemoteParticipant(remoteParticipant)
                    break
                }
            }

            override fun onConnectFailure(room: Room, e: TwilioException) {
                statusTextView!!.text = "Failed to connect"
            }

            override fun onDisconnected(room: Room, e: TwilioException) {
                localParticipant = null
                statusTextView!!.text = "Disconnected from " + room.name
                this@MainActivity.room = null
                enableAudioFocus(false)
                enableVolumeControl(false)
                // Only reinitialize the UI if disconnect was not called from onDestroy()
                if (!disconnectedFromOnDestroy) {
                    moveLocalVideoToPrimaryView()
                }
            }

            override fun onParticipantConnected(room: Room, remoteParticipant: RemoteParticipant) {
                addRemoteParticipant(remoteParticipant)

            }

            override fun onParticipantDisconnected(room: Room, remoteParticipant: RemoteParticipant) {
                removeParticipant(remoteParticipant)
            }

            override fun onRecordingStarted(room: Room) {
                /*
                 * Indicates when media shared to a Room is being recorded. Note that
                 * recording is only available in our Group Rooms developer preview.
                 */
            }

            override fun onRecordingStopped(room: Room) {
                /*
                 * Indicates when media shared to a Room is no longer being recorded. Note that
                 * recording is only available in our Group Rooms developer preview.
                 */
            }
        }
    }

    private fun mediaListener(): RemoteParticipant.Listener {
        return object : RemoteParticipant.Listener {
            override fun onAudioTrackPublished(
                remoteParticipant: RemoteParticipant,
                remoteAudioTrackPublication: RemoteAudioTrackPublication
            ) {
                statusTextView!!.text = "onAudioTrackPublished"
            }

            override fun onAudioTrackUnpublished(
                remoteParticipant: RemoteParticipant,
                remoteAudioTrackPublication: RemoteAudioTrackPublication
            ) {
                statusTextView!!.text = "onAudioTrackPublished"
            }

            override fun onVideoTrackPublished(
                remoteParticipant: RemoteParticipant,
                remoteVideoTrackPublication: RemoteVideoTrackPublication
            ) {
                statusTextView!!.text = "onVideoTrackPublished"
            }

            override fun onVideoTrackUnpublished(
                remoteParticipant: RemoteParticipant,
                remoteVideoTrackPublication: RemoteVideoTrackPublication
            ) {
                statusTextView!!.text = "onVideoTrackUnpublished"
            }

            override fun onDataTrackPublished(
                remoteParticipant: RemoteParticipant,
                remoteDataTrackPublication: RemoteDataTrackPublication
            ) {
                statusTextView!!.text = "onDataTrackPublished"
            }

            override fun onDataTrackUnpublished(
                remoteParticipant: RemoteParticipant,
                remoteDataTrackPublication: RemoteDataTrackPublication
            ) {
                statusTextView!!.text = "onDataTrackUnpublished"
            }

            override fun onAudioTrackSubscribed(
                remoteParticipant: RemoteParticipant,
                remoteAudioTrackPublication: RemoteAudioTrackPublication,
                remoteAudioTrack: RemoteAudioTrack
            ) {
                statusTextView!!.text = "onAudioTrackSubscribed"
            }

            override fun onAudioTrackUnsubscribed(
                remoteParticipant: RemoteParticipant,
                remoteAudioTrackPublication: RemoteAudioTrackPublication,
                remoteAudioTrack: RemoteAudioTrack
            ) {
                statusTextView!!.text = "onAudioTrackUnsubscribed"
            }

            override fun onAudioTrackSubscriptionFailed(
                remoteParticipant: RemoteParticipant,
                remoteAudioTrackPublication: RemoteAudioTrackPublication,
                twilioException: TwilioException
            ) {
                statusTextView!!.text = "onAudioTrackSubscriptionFailed"
            }

            override fun onVideoTrackSubscribed(
                remoteParticipant: RemoteParticipant,
                remoteVideoTrackPublication: RemoteVideoTrackPublication,
                remoteVideoTrack: RemoteVideoTrack
            ) {
                statusTextView!!.text = "onVideoTrackSubscribed"
                addRemoteParticipantVideo(remoteVideoTrack)
            }

            override fun onVideoTrackUnsubscribed(
                remoteParticipant: RemoteParticipant,
                remoteVideoTrackPublication: RemoteVideoTrackPublication,
                remoteVideoTrack: RemoteVideoTrack
            ) {
                statusTextView!!.text = "onVideoTrackUnsubscribed"
                removeParticipantVideo(remoteVideoTrack)
            }

            override fun onVideoTrackSubscriptionFailed(
                remoteParticipant: RemoteParticipant,
                remoteVideoTrackPublication: RemoteVideoTrackPublication,
                twilioException: TwilioException
            ) {
                statusTextView!!.text = "onVideoTrackSubscriptionFailed"
                Toast.makeText(this@MainActivity, String.format(
                    "Failed to subscribe to %s video track",
                    remoteParticipant.identity
                ), Toast.LENGTH_LONG).show()
            }

            override fun onDataTrackSubscribed(
                remoteParticipant: RemoteParticipant,
                remoteDataTrackPublication: RemoteDataTrackPublication,
                remoteDataTrack: RemoteDataTrack
            ) {
                statusTextView!!.text = "onDataTrackSubscribed"
            }

            override fun onDataTrackUnsubscribed(
                remoteParticipant: RemoteParticipant,
                remoteDataTrackPublication: RemoteDataTrackPublication,
                remoteDataTrack: RemoteDataTrack
            ) {
                statusTextView!!.text = "onDataTrackUnsubscribed"
            }

            override fun onDataTrackSubscriptionFailed(
                remoteParticipant: RemoteParticipant,
                remoteDataTrackPublication: RemoteDataTrackPublication,
                twilioException: TwilioException
            ) {
                statusTextView!!.text = "onDataTrackSubscriptionFailed"
            }

            override fun onAudioTrackEnabled(
                remoteParticipant: RemoteParticipant,
                remoteAudioTrackPublication: RemoteAudioTrackPublication
            ) {

            }

            override fun onAudioTrackDisabled(
                remoteParticipant: RemoteParticipant,
                remoteAudioTrackPublication: RemoteAudioTrackPublication
            ) {

            }

            override fun onVideoTrackEnabled(
                remoteParticipant: RemoteParticipant,
                remoteVideoTrackPublication: RemoteVideoTrackPublication
            ) {

            }

            override fun onVideoTrackDisabled(
                remoteParticipant: RemoteParticipant,
                remoteVideoTrackPublication: RemoteVideoTrackPublication
            ) {

            }
        }
    }


    private fun enableAudioFocus(focus: Boolean) {
        if (focus) {
            previousAudioMode = audioManager!!.mode
            // Request audio focus before making any device switch.
            requestAudioFocus()
            /*
             * Use MODE_IN_COMMUNICATION as the default audio mode. It is required
             * to be in this mode when playout and/or recording starts for the best
             * possible VoIP performance. Some devices have difficulties with
             * speaker mode if this is not set.
             */
            audioManager!!.mode = AudioManager.MODE_IN_COMMUNICATION
        } else {
            audioManager!!.mode = previousAudioMode
            audioManager!!.abandonAudioFocus(null)
        }
    }

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener { i -> }
                .build()
            audioManager!!.requestAudioFocus(focusRequest)
        } else {
            audioManager!!.requestAudioFocus(
                null, AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private fun enableVolumeControl(volumeControl: Boolean) {
        if (volumeControl) {
            /*
             * Enable changing the volume using the up/down keys during a conversation
             */
            volumeControlStream = AudioManager.STREAM_VOICE_CALL
        } else {
            volumeControlStream = volumeControlStream
        }
    }

    companion object {
        private val CAMERA_MIC_PERMISSION_REQUEST_CODE = 1
        /*
     * Intent keys used to provide information about a video notification
     */
        val ACTION_VIDEO_NOTIFICATION = "VIDEO_NOTIFICATION"

        /*
     * Intent keys used to obtain a token and register with Twilio Notify
     */
        val ACTION_REGISTRATION = "ACTION_REGISTRATION"
        val REGISTRATION_ERROR = "REGISTRATION_ERROR"
    }

}
