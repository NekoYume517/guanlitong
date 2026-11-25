package com.pu.encrypt;

/**
 * 与官方客户端同名同签名的 JNI 桥，只为复用 libsecure_lib_v2.so 里的 X-Sign 加密。
 *
 * 原生导出符号是 Java_com_pu_encrypt_StringEncryptor_obf_1encrypt_117d4a1，
 * 由静态注册决定，所以本类必须保持包名 com.pu.encrypt、类名 StringEncryptor、
 * 方法名 obf_encrypt_17d4a1 完全一致。
 *
 * 原生库自带反调试（字符串 "Debugger detected in encryption function"），
 * 不做 hook/调试，只在正常进程里调用；加载失败则 encrypt 返回 null。
 */
public final class StringEncryptor {
    private static final boolean LOADED;

    static {
        boolean ok;
        try {
            System.loadLibrary("secure_lib_v2");
            ok = true;
        } catch (Throwable t) {
            ok = false;
            android.util.Log.w("PuLite", "loadLibrary secure_lib_v2 failed: " + t);
        }
        LOADED = ok;
    }

    private StringEncryptor() {
    }

    private static native String obf_encrypt_17d4a1(String plain);

    private static native String obf_decrypt_18f3c2(String cipher);

    public static boolean available() {
        return LOADED;
    }

    /** 对应官方 StringEncryptor.encrypt(String)，失败返回 null */
    public static String encrypt(String plain) {
        if (!LOADED || plain == null || plain.isEmpty() || plain.length() > 1000) return null;
        try {
            return obf_encrypt_17d4a1(plain);
        } catch (Throwable t) {
            android.util.Log.w("PuLite", "native encrypt failed: " + t);
            return null;
        }
    }

    public static String decrypt(String cipher) {
        if (!LOADED || cipher == null || cipher.isEmpty() || cipher.length() > 1000) return null;
        try {
            return obf_decrypt_18f3c2(cipher);
        } catch (Throwable t) {
            return null;
        }
    }
}