# Media3, Compose and AndroidX ship their own consumer rules.
# Keep the playback service name stable for the media session.
-keep class com.powersound.player.PlaybackService { *; }

# Chromecast: Google Play services finds the Cast settings by the class name written in the manifest.
-keep class com.powersound.player.cast.PowerSoundCastOptions { *; }
