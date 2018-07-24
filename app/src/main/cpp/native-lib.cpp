/*
 * Luca Anzalone
 */

#include <jni.h>

#include <string>
#include <vector>
#include <mutex>

#include <android/log.h>

#include <opencv2/opencv.hpp>
#include <opencv2/core/core.hpp>
#include <opencv2/imgproc/imgproc.hpp>

#include <dlib/image_io.h>
#include <dlib/image_processing.h>
#include <dlib/image_processing/generic_image.h>
#include <dlib/image_processing/frontal_face_detector.h>
#include <dlib/opencv/cv_image.h>

#define LOG_TAG "native-lib"
#define LOGD(...) \
  ((void)__android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__))

#define JNI_METHOD(NAME) \
    Java_com_dev_anzalone_luca_tirocinio_Native_##NAME

#define KERNEL_SIZE 15

#define NV21 17
#define YV12 842094169
#define YUV_420_888 35

using namespace std;
//--------------------------------------------------------------------------------------------------
//-- LANDMARK DETECTION
//--------------------------------------------------------------------------------------------------
dlib::shape_predictor shape_predictor;
std::mutex _mutex;
int imageFormat = NV21;

extern "C"
JNIEXPORT void JNICALL
JNI_METHOD(loadModel)(JNIEnv* env, jclass, jstring detectorPath) {

    try {
        const char *path = env->GetStringUTFChars(detectorPath, JNI_FALSE);

        _mutex.lock();
            dlib::deserialize(path) >> shape_predictor;
        _mutex.unlock();

        env->ReleaseStringUTFChars(detectorPath, path); //free mem

        LOGD("JNI: model loaded");

    } catch (dlib::serialization_error &e) {
        LOGD("JNI: failed to model -> %s", e.what());
    }
}
//--------------------------------------------------------------------------------------------------
void rotateMat(cv::Mat &mat, int rotation) {
    if (rotation == 90) { // portrait
        LOGD("JNI: rotation 90");
        cv::transpose(mat, mat);
        cv::flip(mat, mat, -1);
    } else if (rotation == 0) { // landscape-left
        LOGD("JNI: rotation 0");
        cv::flip(mat, mat, 1);
    } else if (rotation == 180) { // landscape-right
        LOGD("JNI: rotation 180");
        cv::flip(mat, mat, 0);
    }
}

extern "C"
JNIEXPORT void JNICALL
JNI_METHOD(setImageFormat)(JNIEnv* env, jclass, jint format) {
    imageFormat = format;
}

extern "C"
JNIEXPORT jlongArray JNICALL
JNI_METHOD(detectLandmarks)(JNIEnv* env, jclass, jbyteArray yuvFrame, jint rotation, jint width, jint height, jint left, jint top, jint right, jint bottom) {
    LOGD("JNI: detectLandmarks");

    // copy content of frame into image
    jbyte *data = env->GetByteArrayElements(yuvFrame, 0);

    // convert yuv-frame to cv::Mat
    cv::Mat yuvMat(height + height / 2, width, CV_8UC1, (unsigned char *) data);
    cv::Mat grayMat(height, width, CV_8UC1);

    // to grayscale
    if (imageFormat == NV21)
        cv::cvtColor(yuvMat, grayMat, CV_YUV2GRAY_NV21);
    else if (imageFormat == YV12)
        cv::cvtColor(yuvMat, grayMat, CV_YUV2GRAY_YV12);

    rotateMat(grayMat, rotation);

    // histogram equalization (to improve contrast)
    cv::equalizeHist(grayMat, grayMat);

    // remove noise (median blur filter)
//    cv::medianBlur(grayMat, grayMat, 7);

    // cv::mat to dlib::image
    dlib::cv_image<unsigned char> image = dlib::cv_image<unsigned char>(grayMat);

    // detect landmark points
    _mutex.lock();
        dlib::rectangle region(left, top, right, bottom);
        dlib::full_object_detection points = shape_predictor(image, region);
    _mutex.unlock();

//    dlib::array2d<unsigned char> cropped;
//    dlib::extract_image_chip(image, region, cropped);
//    dlib::save_jpeg(cropped, "/data/data/com.dev.anzalone.luca.tirocinio/app_images/crop.jpg");

    // result
    auto num_points = points.num_parts();
    jsize len = (jsize) (num_points * sizeof(short)); // num_points * 2

    jlong buffer[len];
    jlongArray result = env->NewLongArray(len);

    // copy points in the buffer
    auto k = 0;
    for (unsigned long i = 0l; i < num_points; ++i) {
        dlib::point p = points.part(i);

//        dlib::draw_solid_circle(image, p, 2.0, dlib::rgb_pixel(255, 255, 0));

        buffer[k++] = p.x();
        buffer[k++] = p.y();
    }

//    if (_count++ > 10) {
//        dlib::draw_rectangle(image, region, dlib::rgb_pixel(255, 255, 0), 2);
//        dlib::save_jpeg(image, "/data/data/com.dev.anzalone.luca.tirocinio/app_images/final.jpg");
//        _count = 0;
//    }

    // setting the content of buffer into result array
    env->SetLongArrayRegion(result, 0, len, buffer);

    // free mem
    env->ReleaseByteArrayElements(yuvFrame, data, 0);

    return result;
}
//--------------------------------------------------------------------------------------------------