# JNI: native-core/jni/airplaycore.cpp binds these by name.
#  - nativeStart/nativeStop are resolved as Java_io_github_samparhizkar_lumen_net_AirPlayCore_*
#  - the nv* forwarders are looked up with GetMethodID and look unused to R8.
-keep class io.github.samparhizkar.lumen.net.AirPlayCore {
    native <methods>;
    private void nv*(...);
}
