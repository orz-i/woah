/* JNI transport only. All geometry and privacy rules are implemented in Rust. */
#include <jni.h>
#include <stdlib.h>
#include "frame_kernel.h"

static void invalid(JNIEnv *env) {
    if (!(*env)->ExceptionCheck(env)) {
        jclass type = (*env)->FindClass(env, "java/lang/IllegalArgumentException");
        if (type) (*env)->ThrowNew(env, type, "Invalid shared privacy core input");
    }
}
static int read_rect(JNIEnv *env, jfloatArray values, FrameKernelRect *rect) {
    if (!values || (*env)->GetArrayLength(env, values) != 4) { invalid(env); return 0; }
    jfloat f[4]; (*env)->GetFloatArrayRegion(env, values, 0, 4, f);
    if ((*env)->ExceptionCheck(env)) return 0;
    *rect = (FrameKernelRect){f[0], f[1], f[2], f[3]}; return 1;
}
static int read_transform(JNIEnv *env, jfloatArray values, FrameKernelTransform *t) {
    if (!values || (*env)->GetArrayLength(env, values) != 7) { invalid(env); return 0; }
    jfloat f[7]; (*env)->GetFloatArrayRegion(env, values, 0, 7, f);
    if ((*env)->ExceptionCheck(env)) return 0;
    for (int i=3;i<7;i++) if (!(f[i]>=1 && f[i]<=65535)) { invalid(env); return 0; }
    *t = (FrameKernelTransform){f[0],f[1],f[2],(uint32_t)f[3],(uint32_t)f[4],(uint32_t)f[5],(uint32_t)f[6]};
    return 1;
}
JNIEXPORT jint JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_abiVersion(JNIEnv *env,jobject self) {
    (void)env; (void)self; return (jint)frame_kernel_abi_version();
}
JNIEXPORT jboolean JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_needsFaceProtectionNative(JNIEnv *env,jobject self,jboolean body,jboolean face) {
    (void)env; (void)self;
    return (frame_kernel_protection_layers(body,face,0) & FRAME_KERNEL_LAYER_FACE) ? JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jlongArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_faceProtectionIdsNative(JNIEnv *env,jobject self,jlongArray body,jlongArray face) {
    (void)self;
    if (!body || !face) { invalid(env); return NULL; }
    jsize nb=(*env)->GetArrayLength(env,body), nf=(*env)->GetArrayLength(env,face);
    size_t total=(size_t)nb+(size_t)nf;
    if (total>65536) { invalid(env); return NULL; }
    if (!total) return (*env)->NewLongArray(env,0);
    jlong *storage=malloc(total*2*sizeof(jlong));
    if (!storage) { invalid(env); return NULL; }
    if (nb) (*env)->GetLongArrayRegion(env,body,0,nb,storage);
    if (nf) (*env)->GetLongArrayRegion(env,face,0,nf,storage+nb);
    jlongArray result=NULL;
    if (!(*env)->ExceptionCheck(env)) {
        int32_t n=frame_kernel_face_targets((const int64_t*)storage,nb,(const int64_t*)(storage+nb),nf,(int64_t*)(storage+total),total);
        if (n<0 || (size_t)n>total) invalid(env);
        else {
            result=(*env)->NewLongArray(env,n);
            if (result) (*env)->SetLongArrayRegion(env,result,0,n,storage+total);
        }
    }
    free(storage);
    return result;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_detailViewsNative(JNIEnv *env,jobject self,jint width,jint height) {
    (void)self;
    FrameKernelRect views[4];
    if (width<2 || height<2 || frame_kernel_detail_views(width,height,views)!=0) { invalid(env); return NULL; }
    jfloatArray result=(*env)->NewFloatArray(env,16);
    jfloat values[16];
    for (int i=0;i<4;i++) {
        values[i*4]=views[i].left; values[i*4+1]=views[i].top;
        values[i*4+2]=views[i].right; values[i*4+3]=views[i].bottom;
    }
    if (result) (*env)->SetFloatArrayRegion(env,result,0,16,values);
    return result;
}
JNIEXPORT jboolean JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_detailCompleteNative(JNIEnv *env,jobject self,jfloatArray box,jfloatArray view,jint width,jint height) {
    (void)self;
    FrameKernelRect b,v;
    if (width<1 || height<1 || !read_rect(env,box,&b) || !read_rect(env,view,&v)) return JNI_FALSE;
    return frame_kernel_detail_complete(b,v,width,height) ? JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jfloat JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_iouNative(JNIEnv *env,jobject self,jfloatArray a,jfloatArray b) {
    (void)self;
    FrameKernelRect x,y;
    if (!read_rect(env,a,&x) || !read_rect(env,b,&y)) return 0;
    return frame_kernel_iou(x,y);
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_reprojectMaskNative(JNIEnv *env,jobject self,jobject mask,jobject output,jfloatArray local,jfloatArray scene,jfloatArray view) {
    (void)self;
    FrameKernelTransform l,s; FrameKernelRect v;
    if (!read_transform(env,local,&l) || !read_transform(env,scene,&s) || !read_rect(env,view,&v)) return;
    uint8_t *input=(*env)->GetDirectBufferAddress(env,mask);
    uint8_t *result=(*env)->GetDirectBufferAddress(env,output);
    jlong input_len=(*env)->GetDirectBufferCapacity(env,mask), output_len=(*env)->GetDirectBufferCapacity(env,output);
    if (!input || !result || input_len<0 || output_len<0 || input==result ||
        frame_kernel_reproject_mask(input,(size_t)input_len,l,s,v,result,(size_t)output_len)!=0) invalid(env);
}
