/*
 * Copyright (C) 2026 yuarmy
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>

#include "libavutil/avutil.h"
#include "libavutil/log.h"

/* 由 CMake 编译 ffmpeg_main.c 时把 main 改名得到。 */
int ffmpeg_main(int argc, char **argv);

/* ffmpeg.c 中的全局状态；ffmpeg_cleanup 不会清零计数器，多次调用前必须手动复位。 */
extern int nb_input_files;
extern int nb_output_files;
extern int nb_filtergraphs;
extern int nb_decoders;

/* CMake 去掉了这两个变量的 static，取消时从 JNI 置位即可中断转码。 */
extern volatile int received_sigterm;
extern volatile int received_nb_signals;

static JavaVM *g_vm;
static pthread_key_t g_attach_key;
static pthread_once_t g_attach_key_once = PTHREAD_ONCE_INIT;
static _Atomic(jobject) g_callback;
static jmethodID g_on_log;
static atomic_bool g_running;

static void detach_thread(void *value)
{
    (void)value;
    if (g_vm)
        (*g_vm)->DetachCurrentThread(g_vm);
}

static void create_attach_key(void)
{
    pthread_key_create(&g_attach_key, detach_thread);
}

/* ffmpeg 的编码/调度线程也会打日志，必须先附加到 JVM；线程退出时自动 detach。 */
static JNIEnv *env_for_thread(void)
{
    JNIEnv *env = NULL;

    if (!g_vm)
        return NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK)
        return env;

    pthread_once(&g_attach_key_once, create_attach_key);

    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK)
        return NULL;
    pthread_setspecific(g_attach_key, (void *)1);
    return env;
}

static void jni_log_callback(void *avcl, int level, const char *fmt, va_list vl)
{
    jobject callback = atomic_load_explicit(&g_callback, memory_order_acquire);
    static __thread int print_prefix = 1;
    char buffer[1024];
    JNIEnv *env;
    jstring line;

    if (!callback)
        return;
    env = env_for_thread();
    if (!env)
        return;

    av_log_format_line2(avcl, level, fmt, vl, buffer, sizeof(buffer), &print_prefix);
    size_t length = strlen(buffer);
    while (length > 0 && (buffer[length - 1] == '\n' || buffer[length - 1] == '\r'))
        buffer[--length] = '\0';

    line = (*env)->NewStringUTF(env, buffer);
    if (!line)
        return;

    (*env)->CallVoidMethod(env, callback, g_on_log, level, line);
    (*env)->DeleteLocalRef(env, line);
    if ((*env)->ExceptionCheck(env))
        (*env)->ExceptionClear(env);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved)
{
    (void)reserved;
    g_vm = vm;
    return JNI_VERSION_1_6;
}

JNIEXPORT jint JNICALL
Java_com_yuarmy_videoconvert_engine_FfmpegNative_run(
    JNIEnv *env, jclass clazz, jobjectArray jargs, jobject callback)
{
    (void)clazz;
    bool expected = false;
    int argc = (*env)->GetArrayLength(env, jargs);
    char **argv;
    jobject callback_ref = NULL;
    jint result = -1;

    if (!atomic_compare_exchange_strong(&g_running, &expected, true))
        return -2;

    /* 先复位信号标志再整理参数：取消若落在参数整理期间，标志会保持置位，
       ffmpeg_main 启动后立即退出，而不是白跑完整段转码。 */
    received_sigterm = 0;
    received_nb_signals = 0;
    nb_input_files = 0;
    nb_output_files = 0;
    nb_filtergraphs = 0;
    nb_decoders = 0;

    argv = calloc((size_t)argc + 1, sizeof(char *));
    if (!argv) {
        result = -4;
        goto done;
    }

    for (int i = 0; i < argc; i++) {
        jstring arg = (jstring)(*env)->GetObjectArrayElement(env, jargs, i);
        const char *text;

        if (!arg)
            continue;
        text = (*env)->GetStringUTFChars(env, arg, NULL);
        if (text) {
            argv[i] = strdup(text);
            (*env)->ReleaseStringUTFChars(env, arg, text);
        }
        (*env)->DeleteLocalRef(env, arg);
        if (!argv[i]) {
            result = -4;
            goto done;
        }
    }

    if (callback) {
        jclass callback_class;

        callback_ref = (*env)->NewGlobalRef(env, callback);
        callback_class = (*env)->GetObjectClass(env, callback_ref);
        g_on_log = (*env)->GetMethodID(env, callback_class, "onLog", "(ILjava/lang/String;)V");
        (*env)->DeleteLocalRef(env, callback_class);
        if (!g_on_log) {
            result = -3;
            goto done;
        }
    }
    atomic_store_explicit(&g_callback, callback_ref, memory_order_release);

    av_log_set_level(AV_LOG_INFO);
    av_log_set_callback(jni_log_callback);

    result = ffmpeg_main(argc, argv);

    av_log(NULL, AV_LOG_QUIET, "%s", "");
    av_log_set_callback(av_log_default_callback);

done:
    atomic_store_explicit(&g_callback, NULL, memory_order_release);
    if (callback_ref)
        (*env)->DeleteGlobalRef(env, callback_ref);
    if (argv) {
        for (int i = 0; i < argc; i++)
            free(argv[i]);
        free(argv);
    }
    atomic_store(&g_running, false);
    return result;
}

JNIEXPORT void JNICALL
Java_com_yuarmy_videoconvert_engine_FfmpegNative_cancel(JNIEnv *env, jclass clazz)
{
    (void)env;
    (void)clazz;
    /* 2 > transcode_init_done，让 AVIO 中断回调对已开始的任务也立即生效。 */
    received_sigterm = 2;
    received_nb_signals = 2;
}

JNIEXPORT jstring JNICALL
Java_com_yuarmy_videoconvert_engine_FfmpegNative_version(JNIEnv *env, jclass clazz)
{
    (void)clazz;
    return (*env)->NewStringUTF(env, av_version_info());
}
