# JNI: native-core/jni/airplaycore.cpp binds these by name.
#  - nativeStart/nativeStop are resolved as Java_com_airplay_receiver_net_AirPlayCore_*
#  - the nv* forwarders are looked up with GetMethodID and look unused to R8.
-keep class com.airplay.receiver.net.AirPlayCore {
    native <methods>;
    private void nv*(...);
}
