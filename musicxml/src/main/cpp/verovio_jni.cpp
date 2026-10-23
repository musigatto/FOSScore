// Hand-written JNI bridge to vrv::Toolkit (no SWIG needed). Strings cross the bridge as UTF-8
// byte arrays: JNI's own string functions use "modified UTF-8", which breaks characters outside
// the Basic Multilingual Plane (SMuFL symbols, some lyrics).
#include <jni.h>
#include <string>

#include "toolkit.h"

namespace {

vrv::Toolkit *tk(jlong handle) { return reinterpret_cast<vrv::Toolkit *>(handle); }

std::string str(JNIEnv *env, jbyteArray utf8)
{
    jsize length = env->GetArrayLength(utf8);
    std::string out(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(utf8, 0, length, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

jbyteArray bytes(JNIEnv *env, const std::string &s)
{
    jbyteArray out = env->NewByteArray(static_cast<jsize>(s.size()));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(s.size()), reinterpret_cast<const jbyte *>(s.data()));
    return out;
}

} // namespace

#define FN(name) Java_com_musigatto_fosscore_musicxml_Verovio_##name

extern "C" {

JNIEXPORT jlong JNICALL FN(nativeCreate)(JNIEnv *env, jclass, jbyteArray resourcePath)
{
    auto *toolkit = new vrv::Toolkit(false);
    toolkit->SetResourcePath(str(env, resourcePath));
    return reinterpret_cast<jlong>(toolkit);
}

JNIEXPORT void JNICALL FN(nativeDestroy)(JNIEnv *, jclass, jlong h) { delete tk(h); }

JNIEXPORT jbyteArray JNICALL FN(nativeVersion)(JNIEnv *env, jclass, jlong h) { return bytes(env, tk(h)->GetVersion()); }

JNIEXPORT jboolean JNICALL FN(nativeSetOptions)(JNIEnv *env, jclass, jlong h, jbyteArray json)
{
    return tk(h)->SetOptions(str(env, json));
}

JNIEXPORT jboolean JNICALL FN(nativeLoadData)(JNIEnv *env, jclass, jlong h, jbyteArray data)
{
    return tk(h)->LoadData(str(env, data));
}

JNIEXPORT void JNICALL FN(nativeRedoLayout)(JNIEnv *env, jclass, jlong h, jbyteArray json)
{
    tk(h)->RedoLayout(str(env, json));
}

JNIEXPORT jint JNICALL FN(nativePageCount)(JNIEnv *, jclass, jlong h) { return tk(h)->GetPageCount(); }

JNIEXPORT jbyteArray JNICALL FN(nativeRenderSvg)(JNIEnv *env, jclass, jlong h, jint page)
{
    return bytes(env, tk(h)->RenderToSVG(page, true));
}

JNIEXPORT jbyteArray JNICALL FN(nativeRenderMidi)(JNIEnv *env, jclass, jlong h)
{
    return bytes(env, tk(h)->RenderToMIDI());
}

JNIEXPORT jbyteArray JNICALL FN(nativeRenderTimemap)(JNIEnv *env, jclass, jlong h)
{
    // with where the measures start: a new tempo takes over on a barline
    return bytes(env, tk(h)->RenderToTimemap(R"({"includeMeasures": true})"));
}

JNIEXPORT jbyteArray JNICALL FN(nativeGetMei)(JNIEnv *env, jclass, jlong h) { return bytes(env, tk(h)->GetMEI("")); }

JNIEXPORT jint JNICALL FN(nativePageWithElement)(JNIEnv *env, jclass, jlong h, jbyteArray id)
{
    return tk(h)->GetPageWithElement(str(env, id));
}

JNIEXPORT jbyteArray JNICALL FN(nativeLog)(JNIEnv *env, jclass, jlong h) { return bytes(env, tk(h)->GetLog()); }

} // extern "C"
