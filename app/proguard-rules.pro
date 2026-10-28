# Media3, Compose and AndroidX ship their own consumer rules.
# Keep the playback service name stable for the media session.
-keep class com.powersound.player.PlaybackService { *; }
