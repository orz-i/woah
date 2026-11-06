/* JNI transport only. All geometry and privacy rules are implemented in Rust. */
#include <jni.h>
#include "woah_privacy_core.h"

static void invalid(JNIEnv *env) {
    if (!(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (type) (*env)->ThrowNew(env, type, "Invalid shared privacy core input");
    }
}
static int read_rect(JNIEnv *env, jfloatArray values, WoahRect *rect) {
    if (!values || (*env)->GetArrayLength(env, values) != 4) { invalid(env); return 0; }
    jfloat f[4]; (*env)->GetFloatArrayRegion(env, values, 0, 4, f);
    if ((*env)->ExceptionCheck(env)) return 0;
    *rect = (WoahRect){f[0], f[1], f[2], f[3]}; return 1;
}
static int read_transform(JNIEnv *env, jfloatArray values, WoahTransform *t) {
    if (!values || (*env)->GetArrayLength(env, values) != 7) { invalid(env); return 0; }
    jfloat f[7]; (*env)->GetFloatArrayRegion(env, values, 0, 7, f);
    if ((*env)->ExceptionCheck(env)) return 0;
    for (int i=3;i<7;i++) if (!(f[i]>=1 && f[i]<=65535)) { invalid(env); return 0; }
    *t = (WoahTransform){f[0],f[1],f[2],(uint32_t)f[3],(uint32_t)f[4],(uint32_t)f[5],(uint32_t)f[6]};
    return 1;
}
JNIEXPORT jint JNICALL Java_art_gaoge_dance_engine_core_PrivacyCore_abiVersion(JNIEnv *env,jobject self) {
    (void)env; (void)self; return (jint)woah_core_abi_version();
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_PrivacyCore_detailViewsNative(JNIEnv *env,jobject self,jint width,jint height) {
    (void)self;
    WoahRect views[4];
    if (width<2 || height<2 || woah_core_detail_views(width,height,views)!=0) { invalid(env); return NULL; }
    jfloatArray result=(*env)->NewFloatArray(env,16);
    jfloat values[16];
    for (int i=0;i<4;i++) {
        values[i*4]=views[i].left; values[i*4+1]=views[i].top;
        values[i*4+2]=views[i].right; values[i*4+3]=views[i].bottom;
    }
    if (result) (*env)->SetFloatArrayRegion(env,result,0,16,values);
    return result;
}
JNIEXPORT jboolean JNICALL Java_art_gaoge_dance_engine_core_PrivacyCore_detailCompleteNative(JNIEnv *env,jobject self,jfloatArray box,jfloatArray view,jint width,jint height) {
    (void)self;
    WoahRect b,v;
    if (width<1 || height<1 || !read_rect(env,box,&b) || !read_rect(env,view,&v)) return JNI_FALSE;
    return woah_core_detail_complete(b,v,width,height) ? JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jfloat JNICALL Java_art_gaoge_dance_engine_core_PrivacyCore_iouNative(JNIEnv *env,jobject self,jfloatArray a,jfloatArray b) {
    (void)self;
    WoahRect x,y;
    if (!read_rect(env,a,&x) || !read_rect(env,b,&y)) return 0;
    return woah_core_iou(x,y);
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_PrivacyCore_reprojectMaskNative(JNIEnv *env,jobject self,jobject mask,jobject output,jfloatArray local,jfloatArray scene,jfloatArray view) {
    (void)self;
    WoahTransform l,s; WoahRect v;
    if (!read_transform(env,local,&l) || !read_transform(env,scene,&s) || !read_rect(env,view,&v)) return;
    uint8_t *input=(*env)->GetDirectBufferAddress(env,mask);
    uint8_t *result=(*env)->GetDirectBufferAddress(env,output);
    jlong input_len=(*env)->GetDirectBufferCapacity(env,mask), output_len=(*env)->GetDirectBufferCapacity(env,output);
    if (!input || !result || input_len<0 || output_len<0 || input==result ||
        woah_core_reproject_mask(input,(size_t)input_len,l,s,v,result,(size_t)output_len)!=0) invalid(env);
}
