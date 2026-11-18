# Media3, Compose and AndroidX ship their own consumer rules.
# Keep the playback service name stable for the media session.
-keep class com.powersound.player.PlaybackService { *; }

# Chromecast: Google Play services finds the Cast settings by the class name written in the manifest.
-keep class com.powersound.player.cast.PowerSoundCastOptions { *; }

# SMB (Library → Network → Windows shared folders, smbj): its event bus finds @Handler methods by reflection.
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
# Parts of smbj, BouncyCastle and slf4j for desktop Java (Kerberos, JNDI, javax.el…) that Android never reaches.
-dontwarn javax.el.**
-dontwarn javax.naming.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.security.**
-dontwarn com.ibm.security.**
-dontwarn sun.security.**
-dontwarn java.lang.management.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**
-dontwarn net.engio.**
-dontwarn com.hierynomus.**
