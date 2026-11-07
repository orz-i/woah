/* JNI transport only. All geometry and privacy rules are implemented in Rust. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <math.h>
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
static jintArray match_output(JNIEnv *env,FrameKernelMatchPair *pairs,int32_t count) {
    if (count<0) { invalid(env); return NULL; }
    jintArray result=(*env)->NewIntArray(env,count*2);
    if (!result || !count) return result;
    jint *values=malloc((size_t)count*2*sizeof(jint));
    if (!values) { invalid(env); return NULL; }
    for (int32_t i=0;i<count;i++) { values[i*2]=(jint)pairs[i].row;values[i*2+1]=(jint)pairs[i].column; }
    (*env)->SetIntArrayRegion(env,result,0,count*2,values);free(values);return result;
}
JNIEXPORT jboolean JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_reservationReleaseNative(JNIEnv *env,jobject self,jint flags,jint owners,jfloat box,jfloat mask) {
    (void)self;
    int32_t result=owners<0 ? -1:frame_kernel_reservation_release(flags,owners,box,mask);
    if(result<0) invalid(env);
    return result==1 ? JNI_TRUE:JNI_FALSE;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_associationDefaultsNative(JNIEnv *env,jobject self) {
    (void)self;FrameKernelAssociationWeights c=frame_kernel_association_defaults();
    jfloat values[5]={c.bounds,c.mask,c.motion,c.direction,c.gating_threshold};jfloatArray out=(*env)->NewFloatArray(env,5);
    if(out) (*env)->SetFloatArrayRegion(env,out,0,5,values);return out;
}
JNIEXPORT jfloat JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_associationScoreNative(JNIEnv *env,jobject self,
    jfloatArray values,jlong history_age,jint flags,jfloatArray weights) {
    (void)self;jfloat v[18],w[5];float score=0;
    if(!values || !weights || (*env)->GetArrayLength(env,values)!=18 || (*env)->GetArrayLength(env,weights)!=5 || history_age<0 || flags<0) {invalid(env);return 0;}
    (*env)->GetFloatArrayRegion(env,values,0,18,v);(*env)->GetFloatArrayRegion(env,weights,0,5,w);
    if((*env)->ExceptionCheck(env)) return 0;
    FrameKernelAssociationEvidence e={{v[0],v[1],v[2],v[3]},{v[4],v[5],v[6],v[7]},{v[8],v[9],v[10],v[11]},
        v[12],v[13],v[14],v[15],v[16],v[17],(int64_t)history_age,(uint32_t)flags};
    FrameKernelAssociationWeights c={w[0],w[1],w[2],w[3],w[4]};
    if(frame_kernel_association_score(e,c,&score)) invalid(env);return score;
}
typedef struct IdInput { jlongArray array; jlong *values; jsize count; } IdInput;
static int read_ids(JNIEnv *env,jlongArray array,IdInput *input) {
    if (!array) {invalid(env);return 0;}
    input->array=array;input->count=(*env)->GetArrayLength(env,array);
    if (input->count>100000) {invalid(env);return 0;}
    input->values=input->count ? (*env)->GetLongArrayElements(env,array,NULL):NULL;
    return !input->count || input->values!=NULL;
}
static void release_ids(JNIEnv *env,IdInput *input) {
    if (input->values) (*env)->ReleaseLongArrayElements(env,input->array,input->values,JNI_ABORT);
}
JNIEXPORT jint JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_sceneScopeNative(JNIEnv *env,jobject self,
    jlongArray roots,jlongArray body,jlongArray face,jboolean has_hero,jlong hero) {
    (void)self;IdInput r={0},b={0},f={0};int result=-1;
    if (read_ids(env,roots,&r) && read_ids(env,body,&b) && read_ids(env,face,&f))
        result=frame_kernel_scene_scope((const int64_t*)r.values,(size_t)r.count,(const int64_t*)b.values,(size_t)b.count,
            (const int64_t*)f.values,(size_t)f.count,has_hero ? 1u:0u,(int64_t)hero);
    release_ids(env,&r);release_ids(env,&b);release_ids(env,&f);if (result<0) invalid(env);return result;
}
JNIEXPORT jlongArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_compositorTargetsNative(JNIEnv *env,jobject self,
    jlongArray selected,jlongArray covered,jlongArray explicit_ids) {
    (void)self;IdInput s={0},c={0},e={0};jlongArray result=NULL;int64_t *out=NULL;
    if (read_ids(env,selected,&s) && read_ids(env,covered,&c) && read_ids(env,explicit_ids,&e)) {
        out=s.count ? malloc((size_t)s.count*sizeof(int64_t)):NULL;
        if (s.count && !out) {invalid(env);}else{
            int n=frame_kernel_compositor_targets((const int64_t*)s.values,(size_t)s.count,(const int64_t*)c.values,(size_t)c.count,
                (const int64_t*)e.values,(size_t)e.count,out,(size_t)s.count);
            if(n<0) {invalid(env);}else{result=(*env)->NewLongArray(env,n);if(result && n) (*env)->SetLongArrayRegion(env,result,0,n,(const jlong*)out);}
        }
    }
    free(out);release_ids(env,&s);release_ids(env,&c);release_ids(env,&e);return result;
}
JNIEXPORT jint JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_currentHeroIndexNative(JNIEnv *env,jobject self,jfloatArray boxes,jfloatArray observed) {
    (void)self;FrameKernelRect hero={0};int result=-2;
    if (!boxes || (observed && !read_rect(env,observed,&hero))) {invalid(env);return result;}
    jsize len=(*env)->GetArrayLength(env,boxes);if (len%4 || len/4>100000) {invalid(env);return result;}
    jfloat *input=len ? (*env)->GetFloatArrayElements(env,boxes,NULL):NULL;
    if(len && !input) return result;
    result=frame_kernel_current_hero_index((const FrameKernelRect*)input,(size_t)len/4,observed ? 1u:0u,hero);
    if(input) (*env)->ReleaseFloatArrayElements(env,boxes,input,JNI_ABORT);
    if(result< -1) invalid(env);return result;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_boxFilterStepNative(JNIEnv *env,jobject self,
    jfloatArray values,jlongArray clock,jint operation,jfloatArray bounds,jlong pts,jfloat factor) {
    (void)self;FrameKernelBoxFilter state;FrameKernelRect box={0},out;float distance=0;
    if (!values || !clock || (*env)->GetArrayLength(env,values)!=72 || (*env)->GetArrayLength(env,clock)!=1 || operation<0) {invalid(env);return NULL;}
    (*env)->GetFloatArrayRegion(env,values,0,8,state.mean);
    (*env)->GetFloatArrayRegion(env,values,8,64,state.covariance);
    jlong time;(*env)->GetLongArrayRegion(env,clock,0,1,&time);state.timestamp_us=(int64_t)time;
    if ((*env)->ExceptionCheck(env) || (bounds && !read_rect(env,bounds,&box))) return NULL;
    if (frame_kernel_box_filter_step(&state,(uint32_t)operation,box,(int64_t)pts,factor,&out,&distance)) {invalid(env);return NULL;}
    jfloatArray result=(*env)->NewFloatArray(env,5);if (!result) return NULL;
    jfloat output[5]={out.left,out.top,out.right,out.bottom,distance};
    (*env)->SetFloatArrayRegion(env,result,0,5,output);
    (*env)->SetFloatArrayRegion(env,values,0,8,state.mean);(*env)->SetFloatArrayRegion(env,values,8,64,state.covariance);
    time=(jlong)state.timestamp_us;(*env)->SetLongArrayRegion(env,clock,0,1,&time);return result;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_invertMatrix4Native(JNIEnv *env,jobject self,jfloatArray matrix) {
    (void)self;float input[16],output[16];
    if (!matrix || (*env)->GetArrayLength(env,matrix)!=16) {invalid(env);return NULL;}
    (*env)->GetFloatArrayRegion(env,matrix,0,16,input);if ((*env)->ExceptionCheck(env)) return NULL;
    int status=frame_kernel_invert_matrix4(input,output);if (status==1) return NULL;if(status) {invalid(env);return NULL;}
    jfloatArray result=(*env)->NewFloatArray(env,16);if (result) (*env)->SetFloatArrayRegion(env,result,0,16,output);return result;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_letterboxNative(JNIEnv *env,jobject self,jint w,jint h,jint ow,jint oh) {
    (void)self;FrameKernelLetterbox p;
    if (w<=0 || h<=0 || ow<=0 || oh<=0 || frame_kernel_letterbox((uint32_t)w,(uint32_t)h,(uint32_t)ow,(uint32_t)oh,&p)) { invalid(env);return NULL; }
    jfloatArray out=(*env)->NewFloatArray(env,5);jfloat values[5]={p.scale,p.width,p.height,p.left,p.top};
    if (out) (*env)->SetFloatArrayRegion(env,out,0,5,values);return out;
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_normalizePixelsNative(JNIEnv *env,jobject self,
    jobject input,jint w,jint h,jint row_stride,jint channels,jint red,jint green,jint blue,
    jboolean flip_rows,jboolean flip_columns,jboolean interleaved,jfloat scale,jfloat bias,jfloatArray output) {
    (void)self;
    if (!input || !output || w<=0 || h<=0 || row_stride<=0 || channels<3 || channels>4 || red<0 || green<0 || blue<0) { invalid(env);return; }
    const uint8_t *bytes=(*env)->GetDirectBufferAddress(env,input);jlong len=(*env)->GetDirectBufferCapacity(env,input);
    if (!bytes || len<=0) { invalid(env);return; }
    jfloat *floats=(*env)->GetFloatArrayElements(env,output,NULL);if (!floats) return;
    FrameKernelPixelSpec spec={(uint32_t)w,(uint32_t)h,(uint32_t)row_stride,(uint32_t)channels,
        (uint32_t)red,(uint32_t)green,(uint32_t)blue,flip_rows ? 1u:0u,flip_columns ? 1u:0u,interleaved ? 1u:0u,scale,bias};
    int result=frame_kernel_normalize_pixels(bytes,(size_t)len,spec,floats,(size_t)(*env)->GetArrayLength(env,output));
    (*env)->ReleaseFloatArrayElements(env,output,floats,result ? JNI_ABORT:0);
    if (result) invalid(env);
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_resizeRgbaNative(JNIEnv *env,jobject self,jobject input,
    jint w,jint h,jint stride,jobject output,jint ow,jint oh,jint fill) {
    (void)self;
    if (!input || !output || w<=0 || h<=0 || stride<=0 || ow<=0 || oh<=0 || fill<0 || fill>255) { invalid(env);return; }
    const uint8_t *bytes=(*env)->GetDirectBufferAddress(env,input);uint8_t *out=(*env)->GetDirectBufferAddress(env,output);
    jlong len=(*env)->GetDirectBufferCapacity(env,input),capacity=(*env)->GetDirectBufferCapacity(env,output);
    if (!bytes || !out || bytes==out || len<=0 || capacity<=0 || frame_kernel_resize_rgba(bytes,(size_t)len,(uint32_t)w,(uint32_t)h,
        (uint32_t)stride,out,(size_t)capacity,(uint32_t)ow,(uint32_t)oh,(uint8_t)fill,NULL)) invalid(env);
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_yuv420Native(JNIEnv *env,jobject self,
    jobject y,jobject u,jobject v,jintArray metadata,jobject output) {
    (void)self;
    if (!y || !u || !v || !metadata || !output || (*env)->GetArrayLength(env,metadata)!=21) { invalid(env);return; }
    jint m[21];(*env)->GetIntArrayRegion(env,metadata,0,21,m);if ((*env)->ExceptionCheck(env)) return;
    for (int i=0;i<21;i++) if (m[i]<0) {invalid(env);return;}
    FrameKernelYuvSpec spec;memcpy(&spec,m,sizeof(spec));
    jobject inputs[3]={y,u,v};FrameKernelPlane planes[3];
    for (int i=0;i<3;i++) {
        const uint8_t *p=(*env)->GetDirectBufferAddress(env,inputs[i]);jlong n=(*env)->GetDirectBufferCapacity(env,inputs[i]);
        if (!p || n<=0) {invalid(env);return;}
        planes[i]=(FrameKernelPlane){p,(size_t)n,(uint32_t)m[15+i*2],(uint32_t)m[16+i*2]};
    }
    uint8_t *out=(*env)->GetDirectBufferAddress(env,output);jlong capacity=(*env)->GetDirectBufferCapacity(env,output);
    if (!out || capacity<=0 || frame_kernel_yuv420_rgba(planes[0],planes[1],planes[2],spec,out,(size_t)capacity)) invalid(env);
}
JNIEXPORT jintArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_matchCostsNative(JNIEnv *env,jobject self,
    jfloatArray costs,jint rows,jint columns,jfloat max_cost) {
    (void)self;
    if (!costs || rows<0 || columns<0 || rows>100000 || columns>100000 || (int64_t)rows*columns>1000000) { invalid(env);return NULL; }
    jsize len=(*env)->GetArrayLength(env,costs);
    if ((int64_t)rows*columns!=len) { invalid(env);return NULL; }
    size_t capacity=(size_t)(rows<columns ? rows:columns);
    if (!capacity) return match_output(env,NULL,frame_kernel_match_costs(NULL,0,(uint32_t)rows,(uint32_t)columns,max_cost,NULL,0));
    FrameKernelMatchPair *pairs=malloc(capacity*sizeof(*pairs));
    if (!pairs) { invalid(env);return NULL; }
    jfloat *input=(*env)->GetFloatArrayElements(env,costs,NULL);
    if (!input) { free(pairs);return NULL; }
    int32_t n=frame_kernel_match_costs(input,(size_t)len,(uint32_t)rows,(uint32_t)columns,max_cost,pairs,capacity);
    (*env)->ReleaseFloatArrayElements(env,costs,input,JNI_ABORT);
    jintArray result=match_output(env,pairs,n);free(pairs);return result;
}
JNIEXPORT jintArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_matchRectsNative(JNIEnv *env,jobject self,
    jfloatArray rows,jfloatArray columns,jfloat max_cost) {
    (void)self;
    if (!rows || !columns) { invalid(env);return NULL; }
    jsize nr=(*env)->GetArrayLength(env,rows),nc=(*env)->GetArrayLength(env,columns);
    if (nr%4 || nc%4 || nr/4>100000 || nc/4>100000 || (int64_t)(nr/4)*(nc/4)>1000000) { invalid(env);return NULL; }
    size_t capacity=(size_t)(nr<nc ? nr:nc)/4;
    FrameKernelMatchPair *pairs=capacity ? malloc(capacity*sizeof(*pairs)):NULL;
    if (capacity && !pairs) { invalid(env);return NULL; }
    jfloat *r=nr ? (*env)->GetFloatArrayElements(env,rows,NULL):NULL;
    if (nr && !r) { free(pairs);return NULL; }
    jfloat *c=nc ? (*env)->GetFloatArrayElements(env,columns,NULL):NULL;
    if (nc && !c) { if (r) (*env)->ReleaseFloatArrayElements(env,rows,r,JNI_ABORT);free(pairs);return NULL; }
    int32_t n=frame_kernel_match_rects((const FrameKernelRect*)r,(uint32_t)(nr/4),(const FrameKernelRect*)c,(uint32_t)(nc/4),max_cost,pairs,capacity);
    if (r) (*env)->ReleaseFloatArrayElements(env,rows,r,JNI_ABORT);
    if (c) (*env)->ReleaseFloatArrayElements(env,columns,c,JNI_ABORT);
    jintArray result=match_output(env,pairs,n);free(pairs);return result;
}
static int read_follow(JNIEnv *env,jfloatArray values,jlongArray clock,FrameKernelFollowState *state) {
    if (!values || !clock || (*env)->GetArrayLength(env,values)!=6 || (*env)->GetArrayLength(env,clock)!=3) {
        invalid(env); return 0;
    }
    jfloat f[6];jlong t[3];
    (*env)->GetFloatArrayRegion(env,values,0,6,f);(*env)->GetLongArrayRegion(env,clock,0,3,t);
    if ((*env)->ExceptionCheck(env)) return 0;
    if ((t[1]!=0 && t[1]!=1) || (t[2]!=0 && t[2]!=1)) { invalid(env); return 0; }
    *state=(FrameKernelFollowState){f[0],f[1],f[2],f[3],f[4],f[5],(int64_t)t[0],(uint32_t)t[1],(uint32_t)t[2]};
    return 1;
}
static void write_follow(JNIEnv *env,jfloatArray values,jlongArray clock,const FrameKernelFollowState *state) {
    jfloat f[6]={state->camera_x,state->camera_y,state->target_x,state->target_y,state->velocity_x,state->velocity_y};
    jlong t[3]={(jlong)state->last_pts_us,(jlong)state->initialized,(jlong)state->has_time};
    (*env)->SetFloatArrayRegion(env,values,0,6,f);(*env)->SetLongArrayRegion(env,clock,0,3,t);
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_followResetNative(JNIEnv *env,jobject self,jfloatArray values,jlongArray clock) {
    (void)self;FrameKernelFollowState state;
    if (!read_follow(env,values,clock,&state)) return;
    if (frame_kernel_follow_reset(&state)) { invalid(env); return; }
    write_follow(env,values,clock,&state);
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_followStepNative(JNIEnv *env,jobject self,
    jfloatArray values,jlongArray clock,jfloatArray target,jlong pts,jfloat source,jfloat output,jfloat zoom,jfloat smooth) {
    (void)self;FrameKernelFollowState state;FrameKernelRect box={0},crop;
    if (!read_follow(env,values,clock,&state) || (target && !read_rect(env,target,&box))) return NULL;
    if (frame_kernel_follow_step(&state,box,target ? 1:0,(int64_t)pts,source,output,zoom,smooth,&crop)) { invalid(env); return NULL; }
    jfloatArray result=(*env)->NewFloatArray(env,4);
    if (!result) return NULL;
    jfloat f[4]={crop.left,crop.top,crop.right,crop.bottom};
    (*env)->SetFloatArrayRegion(env,result,0,4,f);write_follow(env,values,clock,&state);
    return result;
}
JNIEXPORT jintArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_fitEvenRatioNative(JNIEnv *env,jobject self,
    jint width,jint height,jint max_height,jint ratio_w,jint ratio_h) {
    (void)self;
    if (width<=0 || height<=0 || max_height<=0 || ratio_w<=0 || ratio_h<=0) return NULL;
    uint32_t size[2];
    if (frame_kernel_fit_even_ratio((uint32_t)width,(uint32_t)height,(uint32_t)max_height,(uint32_t)ratio_w,(uint32_t)ratio_h,size)) return NULL;
    jintArray result=(*env)->NewIntArray(env,2);jint values[2]={(jint)size[0],(jint)size[1]};
    if (result) (*env)->SetIntArrayRegion(env,result,0,2,values);
    return result;
}
JNIEXPORT jbyteArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_maskBytesNative(JNIEnv *env,jobject self,jfloatArray input) {
    (void)self;
    if (!input) { invalid(env); return NULL; }
    jsize n=(*env)->GetArrayLength(env,input);
    jbyteArray result=(*env)->NewByteArray(env,n);
    if (!result || !n) return result;
    uint8_t *bytes=malloc((size_t)n);
    if (!bytes) { invalid(env); return NULL; }
    jfloat *values=(*env)->GetFloatArrayElements(env,input,NULL);
    if (!values) { free(bytes); return NULL; }
    int32_t status=frame_kernel_mask_bytes(values,(size_t)n,bytes,(size_t)n);
    (*env)->ReleaseFloatArrayElements(env,input,values,JNI_ABORT);
    if (status) { free(bytes); invalid(env); return NULL; }
    (*env)->SetByteArrayRegion(env,result,0,n,(const jbyte*)bytes);
    free(bytes); return result;
}
JNIEXPORT jint JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_decodeInstancesNative(JNIEnv *env,jobject self,
    jfloatArray head,jfloatArray proto,jintArray spec_values,jfloatArray transform_values,jfloatArray thresholds_values,
    jfloatArray detection_values,jobject mask_buffer,jlongArray metrics_values) {
    (void)self;
    if (!head || !proto || !spec_values || !thresholds_values || !detection_values || !mask_buffer || !metrics_values ||
        (*env)->GetArrayLength(env,spec_values)!=8 || (*env)->GetArrayLength(env,thresholds_values)!=3 ||
        (*env)->GetArrayLength(env,detection_values)%5 || (*env)->GetArrayLength(env,metrics_values)!=5) {
        invalid(env); return -1;
    }
    FrameKernelTransform transform;
    if (!read_transform(env,transform_values,&transform)) return -1;
    jint s[8]; jfloat t[3];
    (*env)->GetIntArrayRegion(env,spec_values,0,8,s);
    (*env)->GetFloatArrayRegion(env,thresholds_values,0,3,t);
    if ((*env)->ExceptionCheck(env)) return -1;
    FrameKernelInstanceSpec spec={(uint32_t)s[0],(uint32_t)s[1],(uint32_t)s[2],(uint32_t)s[3],(uint32_t)s[4],(uint32_t)s[5],(uint32_t)s[6],(uint32_t)s[7]};
    FrameKernelInstanceThresholds thresholds={t[0],t[1],t[2]};
    size_t capacity=(size_t)(*env)->GetArrayLength(env,detection_values)/5;
    jlong bytes=(*env)->GetDirectBufferCapacity(env,mask_buffer);
    uint8_t *masks=(*env)->GetDirectBufferAddress(env,mask_buffer);
    if (capacity>100000 || bytes<0 || (bytes>0 && !masks)) { invalid(env); return -1; }
    FrameKernelInstanceDetection *detections=capacity ? malloc(capacity*sizeof(*detections)) : NULL;
    if (capacity && !detections) { invalid(env); return -1; }
    jfloat *h=(*env)->GetFloatArrayElements(env,head,NULL);
    if (!h) { free(detections); return -1; }
    jfloat *p=(*env)->GetFloatArrayElements(env,proto,NULL);
    if (!p) { (*env)->ReleaseFloatArrayElements(env,head,h,JNI_ABORT); free(detections); return -1; }
    FrameKernelInstanceMetrics metrics={0};
    int32_t n=frame_kernel_decode_instances(h,(size_t)(*env)->GetArrayLength(env,head),p,(size_t)(*env)->GetArrayLength(env,proto),
        spec,transform,thresholds,detections,capacity,masks,(size_t)bytes,&metrics);
    (*env)->ReleaseFloatArrayElements(env,head,h,JNI_ABORT);
    (*env)->ReleaseFloatArrayElements(env,proto,p,JNI_ABORT);
    if (n==-1) { free(detections); invalid(env); return -1; }
    if (n>0) {
        jfloat *out=malloc((size_t)n*5*sizeof(jfloat));
        if (!out) { free(detections); invalid(env); return -1; }
        for (int32_t i=0;i<n;i++) {
            out[i*5]=detections[i].bounds.left; out[i*5+1]=detections[i].bounds.top;
            out[i*5+2]=detections[i].bounds.right; out[i*5+3]=detections[i].bounds.bottom; out[i*5+4]=detections[i].confidence;
        }
        (*env)->SetFloatArrayRegion(env,detection_values,0,n*5,out); free(out);
    }
    jlong stats[5]={(jlong)metrics.candidate_scan_ns,(jlong)metrics.suppression_ns,(jlong)metrics.mask_decode_ns,(jlong)metrics.mask_iou_ns,(jlong)metrics.materialize_ns};
    (*env)->SetLongArrayRegion(env,metrics_values,0,5,stats);
    free(detections); return n;
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
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_regionStepNative(JNIEnv *env,jobject self,
    jfloatArray values,jlongArray clock,jfloatArray region,jfloatArray anchor,jlong pts,jint kind,jint flags) {
    (void)self;FrameKernelRegionState state={0};FrameKernelRegionMeasurement input={0};FrameKernelEllipse output;
    jfloat v[12],raw[4];jlong time[2];
    if(!values || !clock || !region || (*env)->GetArrayLength(env,values)!=12 ||
        (*env)->GetArrayLength(env,clock)!=2 || (*env)->GetArrayLength(env,region)!=4) {invalid(env);return NULL;}
    (*env)->GetFloatArrayRegion(env,values,0,12,v);(*env)->GetLongArrayRegion(env,clock,0,2,time);
    (*env)->GetFloatArrayRegion(env,region,0,4,raw);
    if((*env)->ExceptionCheck(env) || !read_rect(env,anchor,&input.anchor)) return NULL;
    if(time[1]<0 || time[1]>7 || kind<0 || flags<0) {invalid(env);return NULL;}
    state.output=(FrameKernelEllipse){v[0],v[1],v[2],v[3]};
    state.reference_radius_x=v[4];state.reference_radius_y=v[5];state.reference_width=v[6];state.reference_height=v[7];
    state.anchor=(FrameKernelRect){v[8],v[9],v[10],v[11]};state.timestamp_us=(int64_t)time[0];state.flags=(uint32_t)time[1];
    input.region=(FrameKernelEllipse){raw[0],raw[1],raw[2],raw[3]};input.timestamp_us=(int64_t)pts;
    input.kind=(uint32_t)kind;input.flags=(uint32_t)flags;
    if(frame_kernel_region_step(&state,input,&output)) {invalid(env);return NULL;}
    jfloatArray result=(*env)->NewFloatArray(env,4);if(!result) return NULL;
    jfloat out[4]={output.center_x,output.center_y,output.radius_x,output.radius_y};
    jfloat stored[12]={state.output.center_x,state.output.center_y,state.output.radius_x,state.output.radius_y,
        state.reference_radius_x,state.reference_radius_y,state.reference_width,state.reference_height,
        state.anchor.left,state.anchor.top,state.anchor.right,state.anchor.bottom};
    time[0]=(jlong)state.timestamp_us;time[1]=(jlong)state.flags;
    (*env)->SetFloatArrayRegion(env,result,0,4,out);(*env)->SetFloatArrayRegion(env,values,0,12,stored);
    (*env)->SetLongArrayRegion(env,clock,0,2,time);return result;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_anchorTranslationNative(JNIEnv *env,jobject self,jfloatArray previous,jfloatArray current) {
    (void)self;FrameKernelRect a,b;float out[2];
    if(!read_rect(env,previous,&a) || !read_rect(env,current,&b)) return NULL;
    if(frame_kernel_anchor_translation(a,b,out)) {invalid(env);return NULL;}
    jfloatArray result=(*env)->NewFloatArray(env,2);if(result) (*env)->SetFloatArrayRegion(env,result,0,2,out);return result;
}
JNIEXPORT jlongArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_sceneTargetsNative(JNIEnv *env,jobject self,
    jlongArray roots,jlongArray body,jlongArray face,jlongArray current,jboolean has_hero,jlong hero) {
    (void)self;IdInput r={0},b={0},f={0},c={0};jlongArray result=NULL;FrameKernelSceneTarget *targets=NULL;
    if(read_ids(env,roots,&r) && read_ids(env,body,&b) && read_ids(env,face,&f) && read_ids(env,current,&c)) {
        size_t capacity=(size_t)b.count+(size_t)f.count+(size_t)c.count;
        targets=capacity ? malloc(capacity*sizeof(FrameKernelSceneTarget)):NULL;
        if(capacity && !targets) {invalid(env);}else {
            int32_t count=frame_kernel_scene_targets((const int64_t*)r.values,r.count,(const int64_t*)b.values,b.count,
                (const int64_t*)f.values,f.count,(const int64_t*)c.values,c.count,has_hero ? 1:0,(int64_t)hero,targets,capacity);
            if(count<0) {invalid(env);}else {
                result=(*env)->NewLongArray(env,count*2);
                if(result) for(int32_t i=0;i<count;i++) {jlong pair[2]={(jlong)targets[i].id,(jlong)targets[i].layers};(*env)->SetLongArrayRegion(env,result,i*2,2,pair);}
            }
        }
    }
    free(targets);release_ids(env,&r);release_ids(env,&b);release_ids(env,&f);release_ids(env,&c);return result;
}

JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_continueRegionNative(
    JNIEnv *env,jobject self,jfloatArray state,jlongArray clocks,jfloatArray current,jlong pts,jint kind) {
    (void)self;
    if(!state || !clocks || !current || (*env)->GetArrayLength(env,state)!=8192 ||
       (*env)->GetArrayLength(env,clocks)!=1025 || (*env)->GetArrayLength(env,current)!=8 || kind<0 || kind>2) {invalid(env);return NULL;}
    jlong count=0;(*env)->GetLongArrayRegion(env,clocks,0,1,&count);
    if((*env)->ExceptionCheck(env)) return NULL;
    if(count<0 || count>1024){invalid(env);return NULL;}
    jfloat raw[8];(*env)->GetFloatArrayRegion(env,current,0,8,raw);
    if((*env)->ExceptionCheck(env))return NULL;
    jfloat *values=(*env)->GetFloatArrayElements(env,state,NULL);
    jlong *times=(*env)->GetLongArrayElements(env,clocks,NULL);
    FrameKernelRegionMeasurement *history=count ? calloc((size_t)count,sizeof(*history)):NULL;
    FrameKernelRegionMeasurement *output=calloc(1025,sizeof(*output));
    jfloatArray result=NULL;int commit=JNI_ABORT;
    if(!values || !times || (count && !history) || !output) goto done;
    for(int i=0;i<count;i++) {
        float *v=values+i*8;
        history[i]=(FrameKernelRegionMeasurement){{v[0],v[1],v[2],v[3]},{v[4],v[5],v[6],v[7]},times[i+1],0,0};
    }
    FrameKernelRegionMeasurement m={{raw[0],raw[1],raw[2],raw[3]},{raw[4],raw[5],raw[6],raw[7]},pts,(uint32_t)kind,0};
    int n=frame_kernel_continue_regions(history,(size_t)count,&m,1,pts,1,output,1025);
    if(n<1){invalid(env);goto done;}
    result=(*env)->NewFloatArray(env,4);if(!result)goto done;
    FrameKernelEllipse e=output[n-1].region;jfloat answer[4]={e.center_x,e.center_y,e.radius_x,e.radius_y};
    (*env)->SetFloatArrayRegion(env,result,0,4,answer);if((*env)->ExceptionCheck(env))goto done;
    for(int i=0;i<n-1;i++) {
        FrameKernelRegionMeasurement r=output[i];float *v=values+i*8;
        v[0]=r.region.center_x;v[1]=r.region.center_y;v[2]=r.region.radius_x;v[3]=r.region.radius_y;
        v[4]=r.anchor.left;v[5]=r.anchor.top;v[6]=r.anchor.right;v[7]=r.anchor.bottom;times[i+1]=r.timestamp_us;
    }
    times[0]=n-1;commit=0;
 done:
    free(history);free(output);
    if(times)(*env)->ReleaseLongArrayElements(env,clocks,times,commit);
    if(values)(*env)->ReleaseFloatArrayElements(env,state,values,commit);
    if(!result && !(*env)->ExceptionCheck(env))invalid(env);
    return result;
}

JNIEXPORT jlong JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_regionContextProbeUsNative(JNIEnv *env,jobject self,jlong pts) {
    (void)env;(void)self;return frame_kernel_region_context_probe_us(pts);
}

JNIEXPORT jint JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_workTransitionNative(JNIEnv *env,jobject self,jint state,jint event) {
    (void)self;
    int32_t result=frame_kernel_work_transition((uint32_t)state,(uint32_t)event);
    if(result<0) invalid(env);
    return result;
}

JNIEXPORT jint JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_workCanReplaceNative(JNIEnv *env,jobject self,jint state) {
    (void)self;
    int32_t result=frame_kernel_work_can_replace((uint32_t)state);
    if(result<0) invalid(env);
    return result;
}
JNIEXPORT jlong JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_trackingCreateNative(JNIEnv *env,jobject self,jfloatArray scalars,jintArray integers) {
 (void)self;jfloat f[11];jint n[6];if(!scalars||!integers||(*env)->GetArrayLength(env,scalars)!=11||(*env)->GetArrayLength(env,integers)!=6){invalid(env);return 0;}
 (*env)->GetFloatArrayRegion(env,scalars,0,11,f);(*env)->GetIntArrayRegion(env,integers,0,6,n);if((*env)->ExceptionCheck(env))return 0;
 for(int i=0;i<6;i++)if(n[i]<0){invalid(env);return 0;}
 FrameKernelTrackingOptions c={(uint32_t)n[0],(uint32_t)n[1],(uint32_t)n[2],(uint32_t)n[3],f[0],{f[1],f[2],f[3],f[4],f[5]},f[6],f[7],f[8],f[9],(uint32_t)n[4],f[10],(uint32_t)n[5]};
 uint64_t id=frame_kernel_tracking_create(c);if(!id)invalid(env);return (jlong)id;
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_trackingDestroyNative(JNIEnv *env,jobject self,jlong id){(void)self;if(frame_kernel_tracking_destroy((uint64_t)id))invalid(env);}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_trackingSelectionNative(JNIEnv *env,jobject self,jlong id,jlongArray protected_ids,jlongArray body,jlongArray face,jboolean dormancy){
 (void)self;IdInput p={0},b={0},f={0};int result=-1;
 if(read_ids(env,protected_ids,&p)&&read_ids(env,body,&b)&&read_ids(env,face,&f))result=frame_kernel_tracking_selection((uint64_t)id,(const int64_t*)p.values,p.count,(const int64_t*)b.values,b.count,(const int64_t*)f.values,f.count,dormancy?1u:0u);
 release_ids(env,&p);release_ids(env,&b);release_ids(env,&f);if(result)invalid(env);
}
JNIEXPORT jbyteArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_trackingStepNative(JNIEnv *env,jobject self,jlong id,jint action,jlong at,jfloatArray values,jlongArray offsets,jbyteArray pixels,jlongArray assigned){
 (void)self;jbyteArray result=NULL;FrameKernelTrackingObservation *observations=NULL;jfloat *v=NULL;jbyte *pool=NULL;IdInput off={0},ids={0};uint8_t *snapshot=NULL;
 if(!values||!pixels||!read_ids(env,offsets,&off)||!read_ids(env,assigned,&ids))goto cleanup;
 if((*env)->GetArrayLength(env,values)!=(jlong)off.count*15)goto cleanup;
 jsize bytes=(*env)->GetArrayLength(env,pixels);if(bytes>256*1024*1024)goto cleanup;
 v=off.count?(*env)->GetFloatArrayElements(env,values,NULL):NULL;pool=bytes?(*env)->GetByteArrayElements(env,pixels,NULL):NULL;
 if((off.count&&!v)||(bytes&&!pool))goto cleanup;
 observations=off.count?calloc((size_t)off.count,sizeof(*observations)):NULL;if(off.count&&!observations)goto cleanup;
 for(jsize i=0;i<off.count;i++){
  jfloat *a=v+i*15;
  for(int k=6;k<8;k++)if(!(a[k]>=0&&a[k]<=65535&&isfinite(a[k])))goto cleanup;
  for(int k=11;k<15;k++)if(!(a[k]>=0&&a[k]<=65535&&isfinite(a[k])))goto cleanup;
  if(off.values[i]!=-1){for(int k=6;k<8;k++)if(!(a[k]>=1&&a[k]<=65535))goto cleanup;for(int k=11;k<15;k++)if(!(a[k]>=1&&a[k]<=65535))goto cleanup;}
  observations[i]=(FrameKernelTrackingObservation){{a[0],a[1],a[2],a[3]},a[4],a[5],(uint64_t)off.values[i],(uint32_t)a[6],(uint32_t)a[7],{a[8],a[9],a[10],(uint32_t)a[11],(uint32_t)a[12],(uint32_t)a[13],(uint32_t)a[14]}};
 }
 if(frame_kernel_tracking_step((uint64_t)id,(uint32_t)action,(int64_t)at,observations,off.count,(const uint8_t*)pool,bytes,(const int64_t*)ids.values,ids.count))goto cleanup;
 int64_t size=frame_kernel_tracking_snapshot((uint64_t)id,NULL,0);if(size<0||size>2147483647)goto cleanup;
 snapshot=size?malloc((size_t)size):NULL;if(size&&!snapshot)goto cleanup;
 if(frame_kernel_tracking_snapshot((uint64_t)id,snapshot,(size_t)size)!=size)goto cleanup;
 result=(*env)->NewByteArray(env,(jsize)size);if(result&&size)(*env)->SetByteArrayRegion(env,result,0,(jsize)size,(const jbyte*)snapshot);
 cleanup:free(snapshot);free(observations);if(v)(*env)->ReleaseFloatArrayElements(env,values,v,JNI_ABORT);if(pool)(*env)->ReleaseByteArrayElements(env,pixels,pool,JNI_ABORT);release_ids(env,&off);release_ids(env,&ids);if(!result)invalid(env);return result;
}

JNIEXPORT jlongArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_replayPlanNative(JNIEnv *env,jobject self,jint scope,jint flags,jlong anchor,jlong last,jlong target,jlong step) {
 (void)self;FrameKernelReplayPlan p;
 if(frame_kernel_replay_plan((uint32_t)scope,(uint32_t)flags,(int64_t)anchor,(int64_t)last,(int64_t)target,(int64_t)step,&p)){invalid(env);return NULL;}
 jlong values[7]={(jlong)p.required,(jlong)p.reset,p.start_us,p.step_us,(jlong)p.regular_count,(jlong)p.append_target,p.target_us};jlongArray out=(*env)->NewLongArray(env,7);if(out)(*env)->SetLongArrayRegion(env,out,0,7,values);return out;
}

JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_trackingReserveNative(JNIEnv *env,jobject self,jlong id,jlong minimum) {
 (void)self;if(frame_kernel_tracking_reserve_id_floor((uint64_t)id,(int64_t)minimum))invalid(env);
}

JNIEXPORT jlong JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_classCreateNative(JNIEnv *env,jobject self,jfloatArray values,jint misses,jint flags){
 (void)self;float f[3];if(!values||(*env)->GetArrayLength(env,values)!=3||misses<0||flags<0){invalid(env);return 0;}
 (*env)->GetFloatArrayRegion(env,values,0,3,f);if((*env)->ExceptionCheck(env))return 0;
 uint64_t id=frame_kernel_class_create((FrameKernelClassOptions){f[0],f[1],f[2],(uint32_t)misses,(uint32_t)flags});if(!id)invalid(env);return (jlong)id;
}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_classDestroyNative(JNIEnv *env,jobject self,jlong id){(void)self;if(frame_kernel_class_destroy((uint64_t)id))invalid(env);}
JNIEXPORT void JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_classResetNative(JNIEnv *env,jobject self,jlong id){(void)self;if(frame_kernel_class_reset((uint64_t)id))invalid(env);}
JNIEXPORT jintArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_classUpdateNative(JNIEnv *env,jobject self,jlong id,jfloatArray values,jlongArray offsets,jbyteArray pixels,jlongArray roots){
 (void)self;jintArray result=NULL;FrameKernelTrackingObservation *obs=NULL;FrameKernelClassRoot *hard=NULL;FrameKernelClassDecision *decisions=NULL;
 jfloat *v=NULL;jbyte *pool=NULL;IdInput off={0},r={0};jint *out=NULL;
 if(!values||!pixels||!read_ids(env,offsets,&off)||!read_ids(env,roots,&r))goto cleanup;
 if(off.count>4096||r.count%2||r.count>8192||(*env)->GetArrayLength(env,values)!=(jlong)off.count*15)goto cleanup;
 jsize bytes=(*env)->GetArrayLength(env,pixels);if(bytes>256*1024*1024)goto cleanup;
 v=off.count?(*env)->GetFloatArrayElements(env,values,NULL):NULL;pool=bytes?(*env)->GetByteArrayElements(env,pixels,NULL):NULL;
 if((off.count&&!v)||(bytes&&!pool))goto cleanup;
 obs=off.count?calloc((size_t)off.count,sizeof(*obs)):NULL;decisions=off.count?calloc((size_t)off.count,sizeof(*decisions)):NULL;
 hard=r.count?calloc((size_t)r.count/2,sizeof(*hard)):NULL;if((off.count&&(!obs||!decisions))||(r.count&&!hard))goto cleanup;
 for(jsize i=0;i<off.count;i++){
  jfloat *a=v+i*15;for(int k=6;k<8;k++)if(!(a[k]>=0&&a[k]<=65535&&isfinite(a[k])))goto cleanup;
  if(off.values[i]!=-1){for(int k=6;k<8;k++)if(!(a[k]>=1&&a[k]<=65535))goto cleanup;for(int k=11;k<15;k++)if(!(a[k]>=1&&a[k]<=65535))goto cleanup;}
  obs[i]=(FrameKernelTrackingObservation){{a[0],a[1],a[2],a[3]},a[4],a[5],(uint64_t)off.values[i],(uint32_t)a[6],(uint32_t)a[7],{a[8],a[9],a[10],(uint32_t)a[11],(uint32_t)a[12],(uint32_t)a[13],(uint32_t)a[14]}};
 }
 for(jsize i=0;i<r.count/2;i++){if(r.values[i*2]<0||r.values[i*2]>UINT32_MAX||r.values[i*2+1]<0||r.values[i*2+1]>1)goto cleanup;hard[i]=(FrameKernelClassRoot){(uint32_t)r.values[i*2],(uint32_t)r.values[i*2+1]};}
 uint32_t evaluations=0;int count=frame_kernel_class_update((uint64_t)id,obs,off.count,(const uint8_t*)pool,bytes,hard,r.count/2,decisions,off.count,&evaluations);
 if(count<0)goto cleanup;out=malloc(((size_t)count*3+1)*sizeof(jint));if(!out)goto cleanup;
 out[0]=(jint)evaluations;for(int i=0;i<count;i++){out[i*3+1]=(jint)decisions[i].index;out[i*3+2]=(jint)decisions[i].selection;out[i*3+3]=(jint)decisions[i].unknown;}
 result=(*env)->NewIntArray(env,count*3+1);if(result)(*env)->SetIntArrayRegion(env,result,0,count*3+1,out);
 cleanup:free(out);free(decisions);free(hard);free(obs);if(v)(*env)->ReleaseFloatArrayElements(env,values,v,JNI_ABORT);if(pool)(*env)->ReleaseByteArrayElements(env,pixels,pool,JNI_ABORT);release_ids(env,&off);release_ids(env,&r);if(!result)invalid(env);return result;
}

JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_gridFaceInputNative(JNIEnv *env,jobject self,jobject rgba,jint w,jint h,jint stride) {
 (void)self;void *in=rgba?(*env)->GetDirectBufferAddress(env,rgba):NULL;
 jlong size=rgba?(*env)->GetDirectBufferCapacity(env,rgba):-1;
 if(!in||size<0){invalid(env);return NULL;}
 float *values=malloc(192*192*3*sizeof(float));if(!values){invalid(env);return NULL;}
 int status=frame_kernel_grid_face_input(in,(size_t)size,(uint32_t)w,(uint32_t)h,(uint32_t)stride,values,192*192*3);
 jfloatArray result=NULL;if(!status){result=(*env)->NewFloatArray(env,192*192*3);if(result)(*env)->SetFloatArrayRegion(env,result,0,192*192*3,values);}
 free(values);if(status)invalid(env);return result;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_gridFaceDecodeNative(JNIEnv *env,jobject self,jfloatArray boxes,jfloatArray scores,jint w,jint h,jfloat minimum) {
 (void)self;if(!boxes||!scores||(*env)->GetArrayLength(env,boxes)!=2304*16||(*env)->GetArrayLength(env,scores)!=2304){invalid(env);return NULL;}
 jfloat *b=(*env)->GetFloatArrayElements(env,boxes,NULL);if(!b)return NULL;
 jfloat *s=(*env)->GetFloatArrayElements(env,scores,NULL);if(!s){(*env)->ReleaseFloatArrayElements(env,boxes,b,JNI_ABORT);return NULL;}
 float *out=malloc(2304*17*sizeof(float));jfloatArray result=NULL;
 if(out){int count=frame_kernel_grid_face_decode(b,2304*16,s,2304,(uint32_t)w,(uint32_t)h,minimum,.3f,out,2304*17);
  if(count>=0){result=(*env)->NewFloatArray(env,count*17);if(result&&count)(*env)->SetFloatArrayRegion(env,result,0,count*17,out);}}
 free(out);(*env)->ReleaseFloatArrayElements(env,boxes,b,JNI_ABORT);(*env)->ReleaseFloatArrayElements(env,scores,s,JNI_ABORT);
 if(!result)invalid(env);return result;
}

JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_faceHeadPlanNative(JNIEnv *env,jobject self,jfloatArray box,jint w,jint h) {
 (void)self;FrameKernelRect r;if(!read_rect(env,box,&r))return NULL;
 if(w<=1||h<=1)return NULL;float values[6];if(frame_kernel_face_head_plan(r,(uint32_t)w,(uint32_t)h,values))return NULL;
 jfloatArray out=(*env)->NewFloatArray(env,6);if(out)(*env)->SetFloatArrayRegion(env,out,0,6,values);return out;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_faceCenterNative(JNIEnv *env,jobject self,jfloatArray row) {
 (void)self;float v[18],values[2];if(!row||(*env)->GetArrayLength(env,row)!=18){invalid(env);return NULL;}
 (*env)->GetFloatArrayRegion(env,row,0,18,v);if((*env)->ExceptionCheck(env))return NULL;
 if(frame_kernel_face_center(v,values)){invalid(env);return NULL;}
 jfloatArray out=(*env)->NewFloatArray(env,2);if(out)(*env)->SetFloatArrayRegion(env,out,0,2,values);return out;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_faceSelectNative(JNIEnv *env,jobject self,jfloatArray rows,jfloatArray view,jfloat ax,jfloat ay,jfloat maximum,jfloat separation) {
 (void)self;FrameKernelRect r;if(!read_rect(env,view,&r))return NULL;
 jsize n=rows?(*env)->GetArrayLength(env,rows):-1;
 if(n<0||n%18||n>4096*18){invalid(env);return NULL;}
 if(!n)return NULL;jfloat *v=(*env)->GetFloatArrayElements(env,rows,NULL);if(!v)return NULL;
 float distance=0;int index=frame_kernel_face_select(v,(size_t)n/18,r,ax,ay,maximum,separation,&distance);
 (*env)->ReleaseFloatArrayElements(env,rows,v,JNI_ABORT);
 if(index==-1)return NULL;if(index<0){invalid(env);return NULL;}
 float values[2]={(float)index,distance};jfloatArray out=(*env)->NewFloatArray(env,2);
 if(out)(*env)->SetFloatArrayRegion(env,out,0,2,values);return out;
}
JNIEXPORT jintArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_faceOwnedNative(JNIEnv *env,jobject self,jfloatArray rows,jfloatArray source,jfloat size,jfloatArray person) {
 (void)self;FrameKernelRect r,p;if(!read_rect(env,source,&r)||!read_rect(env,person,&p))return NULL;
 jsize n=rows?(*env)->GetArrayLength(env,rows):-1;if(n<0||n%18||n>4096*18){invalid(env);return NULL;}
 if(!n)return (*env)->NewIntArray(env,0);
 jfloat *v=(*env)->GetFloatArrayElements(env,rows,NULL);if(!v)return NULL;
 jint indices[4096];int count=0,bad=0;
 for(int i=0;i<n/18;i++){int status=frame_kernel_face_owned(v+i*18,r,size,p);if(status<0){bad=1;break;}if(status)indices[count++]=i;}
 (*env)->ReleaseFloatArrayElements(env,rows,v,JNI_ABORT);
 if(bad){invalid(env);return NULL;}jintArray out=(*env)->NewIntArray(env,count);
 if(out&&count)(*env)->SetIntArrayRegion(env,out,0,count,indices);return out;
}

JNIEXPORT jintArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_classSummaryNative(JNIEnv *env,jobject self,jlong id){
 (void)self;uint32_t values[8];if(frame_kernel_class_summary((uint64_t)id,values)){invalid(env);return NULL;}
 jint out[8];for(int i=0;i<8;i++)out[i]=(jint)values[i];jintArray result=(*env)->NewIntArray(env,8);
 if(result)(*env)->SetIntArrayRegion(env,result,0,8,out);return result;
}

/* All new region APIs return FRAME_KERNEL_REGION_OK(0) on success.
   Never convert output buffers for any nonzero status. */
static jfloatArray ellipse_output(JNIEnv *env,FrameKernelEllipse r){
 float values[4]={r.center_x,r.center_y,r.radius_x,r.radius_y};
 jfloatArray out=(*env)->NewFloatArray(env,4);
 if(out)(*env)->SetFloatArrayRegion(env,out,0,4,values);return out;
}
static int read_ellipse(JNIEnv *env,jfloatArray input,FrameKernelEllipse *out){
 if(!input||(*env)->GetArrayLength(env,input)!=4){invalid(env);return 0;}
 float values[4]={0};(*env)->GetFloatArrayRegion(env,input,0,4,values);
 if((*env)->ExceptionCheck(env))return 0;
 *out=(FrameKernelEllipse){values[0],values[1],values[2],values[3]};return 1;
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_faceRegionNative(JNIEnv *env,jobject self,jfloatArray row,jfloatArray view,jfloat size,jfloat yaw){
 (void)self;if(!row||(*env)->GetArrayLength(env,row)!=18){invalid(env);return NULL;}
 float values[18]={0};(*env)->GetFloatArrayRegion(env,row,0,18,values);
 if((*env)->ExceptionCheck(env))return NULL;
 FrameKernelRect rect={0};if(view&&!read_rect(env,view,&rect))return NULL;
 FrameKernelEllipse output={0};
 int32_t status=frame_kernel_detected_face_region(values,view?&rect:NULL,size,yaw,&output);
 if(status!=FRAME_KERNEL_REGION_OK)return NULL;
 return ellipse_output(env,output);
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_headFallbackNative(JNIEnv *env,jobject self,jfloatArray person){
 (void)self;FrameKernelRect rect={0};if(!read_rect(env,person,&rect))return NULL;
 FrameKernelEllipse output={0};
 int32_t status=frame_kernel_head_fallback(rect,&output);
 if(status!=FRAME_KERNEL_REGION_OK)return NULL;
 return ellipse_output(env,output);
}
JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_maskHeadNative(JNIEnv *env,jobject self,jbyteArray pixels,jint width,jint height,jfloatArray transform,jfloatArray person,jfloatArray seed,jfloatArray previous,jfloat expansion){
 (void)self;
 if(!pixels||width<=1||height<=1||width>1024||height>1024||(*env)->GetArrayLength(env,pixels)!=width*height)return NULL;
 FrameKernelHeadMaskSpec spec={0};FrameKernelEllipse trusted={0};FrameKernelRect prior={0};
 spec.width=(uint32_t)width;spec.height=(uint32_t)height;
 if(!read_transform(env,transform,&spec.transform)||!read_rect(env,person,&spec.person)||!read_ellipse(env,seed,&trusted))return NULL;
 if(previous&&!read_rect(env,previous,&prior))return NULL;
 jbyte *bytes=(*env)->GetByteArrayElements(env,pixels,NULL);if(!bytes)return NULL;
 jfloatArray result=NULL;
 if(previous){
  FrameKernelEllipse output={0};
  int32_t status=frame_kernel_trusted_mask_region((const uint8_t*)bytes,(size_t)width*height,spec,prior,trusted,expansion,&output);
  if(status==FRAME_KERNEL_REGION_OK)result=ellipse_output(env,output);
 }else{
  float output[2]={0};
  int32_t status=frame_kernel_mask_head((const uint8_t*)bytes,(size_t)width*height,spec,trusted,output);
  if(status==FRAME_KERNEL_REGION_OK){result=(*env)->NewFloatArray(env,2);if(result)(*env)->SetFloatArrayRegion(env,result,0,2,output);}
 }
 (*env)->ReleaseByteArrayElements(env,pixels,bytes,JNI_ABORT);return result;
}

JNIEXPORT jfloatArray JNICALL Java_art_gaoge_dance_engine_core_FrameKernel_projectFaceRegionNative(JNIEnv *env,jobject self,jfloatArray current,jfloatArray previous,jfloatArray seed,jlong age){
 (void)self;FrameKernelRect c={0},p={0};FrameKernelEllipse trusted={0},output={0};
 if(!read_rect(env,current,&c)||!read_rect(env,previous,&p)||!read_ellipse(env,seed,&trusted))return NULL;
 int32_t status=frame_kernel_project_face_region(c,p,trusted,(int64_t)age,&output);
 if(status!=FRAME_KERNEL_REGION_OK)return NULL;
 return ellipse_output(env,output);
}
