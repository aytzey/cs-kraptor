package com.kraptor;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IInterface;
import android.util.Log;
import android.util.Property;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

/**
 * Gives outdated devices a current WebView for Cloudflare: Chromium's own SystemWebView build is
 * downloaded once from the Chromium snapshot bucket (only the APK entry, verified by SHA-256) and,
 * on the next app start, this process uses it instead of the system WebView.
 *
 * Makes this process use a Chromium WebView APK stored on disk instead of the system WebView,
 * without installing it (idea from github.com/JonaNorman/WebViewUpgrade, Apache-2.0).
 *
 * Plugins cannot declare services, so the WebView renderer runs in this process
 * (isMultiProcessEnabled answers false). Must run before anything in the process touches WebView.
 */
public final class WebViewKernel {
    private static final String TAG = "KraptorKernel";
    private static final String UPDATE_SERVICE = "webviewupdate";
    private static final String UPDATE_INTERFACE = "android.webkit.IWebViewUpdateService";

    /** System WebViews older than this get the bundled kernel; Cloudflare loops on 101. */
    private static final int MIN_SYSTEM_MAJOR = 130;
    private static final String SNAPSHOT = "https://commondatastorage.googleapis.com/chromium-browser-snapshots/";
    private static final String DIRECTORY = "kraptor-webview";
    private static final AtomicBoolean DOWNLOADING = new AtomicBoolean();

    /** Location of apks/SystemWebView.apk (Chromium 160.0.8181.0) inside a snapshot zip. */
    private static final class Kernel {
        final String name, zip; final long offset, compressed, size; final String sha256;
        Kernel(String name, String zip, long offset, long compressed, long size, String sha256) {
            this.name = name; this.zip = zip; this.offset = offset; this.compressed = compressed; this.size = size; this.sha256 = sha256;
        }
    }
    private static final Kernel ARM = new Kernel("arm", "Android/1752261/chrome-android.zip",
        204058781L, 66293678L, 117119720L, "7fde96fef3c59efe972c1b24b75fb0c52261d2b182dff1744cdae6f82c53122e");
    private static final Kernel ARM64 = new Kernel("arm64", "Android_Arm64/1752261/chrome-android.zip",
        282742223L, 158686215L, 318662452L, "ac5cf85252313ecaa512fefa476a41cbd230f5f0ef79d1010c3bc7be1fce31e5");
    private static final Kernel X64 = new Kernel("x64", "AndroidDesktop_x64/1752440/chrome-android-desktop.zip",
        313409499L, 191762846L, 416148290L, "718cb2dcce2a2f4a239a1128b9cd8fcb36720aba811c9838c525259c532867cd");

    /**
     * Each plugin ships its own copy of this class (one class loader per plugin), so `synchronized` on the
     * class would not keep HDFilmCehennemi and FilmMakinesi apart. String literals are interned VM-wide:
     * every copy locks the same object.
     */
    private static final String PROPERTY = "kraptor.webview.kernel";

    private WebViewKernel() { }

    /**
     * Called from plugin load(), before the app creates any WebView. Installs the kernel when the
     * system WebView is outdated and the kernel is on disk; otherwise starts its download.
     */
    public static void prepare(Context context) {
        try {
            if (System.getProperty(PROPERTY) != null) return;
            if (Build.VERSION.SDK_INT < 29) return;  // the pinned Chromium needs Android 10
            File dir = new File(context.getFilesDir(), DIRECTORY);
            boolean wanted = new File(dir, "wanted").exists();  // Cloudflare looped on this device before
            int major = major(systemVersion());
            if (major >= MIN_SYSTEM_MAJOR && !wanted) return;
            if (kernel() == null) return;
            File apk = apk(context);
            if (new File(apk.getPath() + ".ok").isFile() && apk.length() == kernel().size) {
                install(context, apk);
            } else {
                Log.d(TAG, "system WebView " + major + " is outdated; downloading kernel");
                fetch(context);
            }
        } catch (Throwable error) {
            Log.w(TAG, "prepare failed", error);
        }
    }

    /** Download the kernel in the background (used once Cloudflare has looped in the system WebView). */
    public static void fetch(final Context context) {
        if (Build.VERSION.SDK_INT < 29 || kernel() == null || !DOWNLOADING.compareAndSet(false, true)) return;
        Thread worker = new Thread(new Runnable() {
            @Override public void run() {
                try { download(context.getApplicationContext()); }
                catch (Throwable error) { Log.w(TAG, "kernel download failed", error); }
                finally { DOWNLOADING.set(false); }
            }
        }, "KraptorKernel");
        worker.setDaemon(true);
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    /** Kernel matching this process' ABI, or null (32-bit x86 has no build). */
    private static Kernel kernel() {
        if (android.os.Process.is64Bit()) return Build.SUPPORTED_64_BIT_ABIS[0].startsWith("x86") ? X64 : ARM64;
        return Build.SUPPORTED_32_BIT_ABIS[0].startsWith("arm") ? ARM : null;
    }

    private static File apk(Context context) {
        return new File(new File(context.getFilesDir(), DIRECTORY), "160.0.8181.0-" + kernel().name + ".apk");
    }

    private static int major(String version) {
        if (version == null) return 0;
        try { return Integer.parseInt(version.split("\\.")[0]); } catch (NumberFormatException ignored) { return 0; }
    }

    private static void download(Context context) throws Exception {
        Kernel kernel = kernel();
        File apk = apk(context);
        File dir = apk.getParentFile();
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IllegalStateException("cannot create " + dir);
        new File(dir, "wanted").createNewFile();
        File ok = new File(apk.getPath() + ".ok");
        if (ok.isFile() && apk.length() == kernel.size) return;
        // Several plugins share this directory; one download at a time.
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(dir, "lock"), "rw");
             FileLock lock = tryLock(lockFile)) {
            if (lock == null) return;  // another plugin is downloading
            File partial = new File(apk.getPath() + ".part");
            HttpURLConnection connection = (HttpURLConnection) new URL(SNAPSHOT + kernel.zip).openConnection();
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("Range", "bytes=" + kernel.offset + "-" + (kernel.offset + 30 + 1024 + kernel.compressed));
            if (connection.getResponseCode() != 206) throw new IllegalStateException("HTTP " + connection.getResponseCode());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long written = 0;
            try (DataInputStream in = new DataInputStream(connection.getInputStream());
                 OutputStream out = new FileOutputStream(partial)) {
                byte[] header = new byte[30];
                in.readFully(header);
                if (header[0] != 'P' || header[1] != 'K' || header[2] != 3 || header[3] != 4) throw new IllegalStateException("no zip entry");
                int skip = (header[26] & 0xff) | (header[27] & 0xff) << 8 | (header[28] & 0xff) << 16 | (header[29] & 0xff) << 24;
                in.readFully(new byte[(skip & 0xffff) + (skip >>> 16)]);
                InputStream data = new InflaterInputStream(new Bounded(in, kernel.compressed), new Inflater(true), 65536);
                byte[] buffer = new byte[65536];
                for (int n; (n = data.read(buffer)) > 0; ) {
                    out.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    written += n;
                }
            } finally {
                connection.disconnect();
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            if (written != kernel.size || !hex.toString().equals(kernel.sha256)) {
                partial.delete();
                throw new IllegalStateException("kernel checksum mismatch (" + written + " bytes)");
            }
            if (!partial.renameTo(apk)) throw new IllegalStateException("cannot store " + apk);
            apk.setReadOnly();
            ok.createNewFile();
            Log.d(TAG, "kernel downloaded: " + apk + "; used from the next app start");
        }
    }

    /** Plugins share one process, where a second lock on the same file throws instead of returning null. */
    private static FileLock tryLock(RandomAccessFile file) throws java.io.IOException {
        try { return file.getChannel().tryLock(); }
        catch (java.nio.channels.OverlappingFileLockException busy) { return null; }
    }

    /** Stops the inflater at the end of the compressed entry. */
    private static final class Bounded extends InputStream {
        private final InputStream in; private long left;
        Bounded(InputStream in, long length) { this.in = in; this.left = length; }
        @Override public int read() throws java.io.IOException {
            if (left <= 0) return -1;
            int b = in.read();
            if (b >= 0) left--;
            return b;
        }
        @Override public int read(byte[] b, int off, int len) throws java.io.IOException {
            if (left <= 0) return -1;
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) left -= n;
            return n;
        }
    }

    /** Returns true when the process will use {@code apk} as its WebView. */
    public static boolean install(Context context, File apk) {
        synchronized (PROPERTY) {
            return installLocked(context, apk);
        }
    }

    private static boolean installLocked(Context context, File apk) {
        if (System.getProperty(PROPERTY) != null) return true;  // another plugin did it
        try {
            if (Build.VERSION.SDK_INT < 29) {  // the pinned Chromium needs Android 10
                Log.d(TAG, "unsupported Android " + Build.VERSION.SDK_INT);
                return false;
            }
            exemptHiddenApis();
            Class<?> factory = Class.forName("android.webkit.WebViewFactory");
            if (staticField(factory, "sProviderInstance") != null) {
                Log.d(TAG, "WebView already initialised in this process");
                return false;
            }
            final PackageInfo kernel = archive(context, apk, PackageManager.GET_META_DATA | PackageManager.GET_SIGNATURES);
            if (kernel == null) { Log.d(TAG, "not an APK: " + apk); return false; }
            apk.setReadOnly();  // Android 14+ refuses writable code files
            disablePackageInfoCache();
            hookPackageManager(context, kernel.packageName, apk);
            hookUpdateService(kernel);
            singleProcess(factory);
            System.setProperty(PROPERTY, kernel.versionName);
            Log.d(TAG, "WebView kernel " + kernel.packageName + " " + kernel.versionName + " from " + apk);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "install failed", error);
            return false;
        }
    }

    /** Version of the system WebView, without initialising it. */
    public static String systemVersion() {
        try {
            PackageInfo info = (PackageInfo) Class.forName("android.webkit.WebView")
                .getMethod("getCurrentWebViewPackage").invoke(null);
            return info == null ? null : info.versionName;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Opens the non-SDK interfaces this class needs. android.util.Property lists VMRuntime's methods from
     * framework code (LSPosed "LSPass", Apache-2.0); Android 10 also accepts double reflection.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void exemptHiddenApis() throws Exception {
        try {
            Property<Class, Method[]> methods = Property.of(Class.class, Method[].class, "DeclaredMethods");
            Class<?> runtime = Class.forName("dalvik.system.VMRuntime");
            Method getRuntime = null, exempt = null;
            for (Method method : methods.get(runtime)) {
                if (method.getName().equals("getRuntime") && method.getParameterTypes().length == 0) getRuntime = method;
                if (method.getName().equals("setHiddenApiExemptions")) exempt = method;
            }
            if (getRuntime != null && exempt != null) {
                getRuntime.setAccessible(true);
                exempt.setAccessible(true);
                exempt.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
                return;
            }
        } catch (Throwable error) {
            Log.d(TAG, "Property exemption failed: " + error);
        }
        Method forName = Class.class.getDeclaredMethod("forName", String.class);
        Method getDeclaredMethod = Class.class.getDeclaredMethod("getDeclaredMethod", String.class, Class[].class);
        Class<?> runtime = (Class<?>) forName.invoke(null, "dalvik.system.VMRuntime");
        Method getRuntime = (Method) getDeclaredMethod.invoke(runtime, "getRuntime", null);
        Method exempt = (Method) getDeclaredMethod.invoke(runtime, "setHiddenApiExemptions", new Class[]{String[].class});
        exempt.invoke(getRuntime.invoke(null), new Object[]{new String[]{"L"}});
    }

    private static PackageInfo archive(Context context, File apk, int flags) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageArchiveInfo(apk.getPath(), flags);
        if (info == null) return null;
        ApplicationInfo app = info.applicationInfo;
        app.sourceDir = app.publicSourceDir = apk.getPath();
        File libs = new File(apk.getParentFile(), "kernel-libs");
        if (!libs.mkdirs() && !libs.isDirectory()) throw new IllegalStateException("cannot create " + libs);
        app.nativeLibraryDir = libs.getPath();
        // extractNativeLibs=false: libraries load straight from "<apk>!/lib/<abi>".
        String abi = Build.SUPPORTED_ABIS[0];
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(apk)) {
            for (String candidate : Build.SUPPORTED_ABIS) {
                if (zip.getEntry("lib/" + candidate + "/libwebviewchromium.so") != null) { abi = candidate; break; }
            }
        }
        setField(app, "primaryCpuAbi", abi);
        app.flags |= ApplicationInfo.FLAG_INSTALLED | ApplicationInfo.FLAG_HAS_CODE;
        app.enabled = true;
        return info;
    }

    private static void hookPackageManager(final Context context, final String packageName, final File apk) throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Method getPackageManager = activityThread.getDeclaredMethod("getPackageManager");
        final Object original = getPackageManager.invoke(null);
        Class<?> pmInterface = Class.forName("android.content.pm.IPackageManager");
        Object proxy = Proxy.newProxyInstance(pmInterface.getClassLoader(), new Class<?>[]{pmInterface},
            new InvocationHandler() {
                @Override public Object invoke(Object self, Method method, Object[] args) throws Throwable {
                    String name = method.getName();
                    if (args != null && args.length > 0 && packageName.equals(args[0])) {
                        int flags = args.length > 1 && args[1] instanceof Number ? ((Number) args[1]).intValue() : 0;
                        if ("getPackageInfo".equals(name)) return archive(context, apk, flags | PackageManager.GET_META_DATA);
                        if ("getApplicationInfo".equals(name)) return archive(context, apk, flags | PackageManager.GET_META_DATA).applicationInfo;
                    }
                    // The kernel's own components (e.g. SafeModeState) are not installed: report them disabled.
                    if ("getComponentEnabledSetting".equals(name) && args != null && args.length > 0
                            && args[0] instanceof android.content.ComponentName
                            && packageName.equals(((android.content.ComponentName) args[0]).getPackageName())) {
                        return PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                    }
                    try { return method.invoke(original, args); }
                    catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                }
            });
        setStaticField(activityThread, "sPackageManager", proxy);
        // Package managers created earlier keep their own reference.
        Object application = activityThread.getDeclaredMethod("currentApplication").invoke(null);
        for (Context ctx : new Context[]{context, (Context) application,
                application == null ? null : ((Context) application).getApplicationContext()}) {
            if (ctx == null) continue;
            PackageManager manager = ctx.getPackageManager();
            Field mPM = findField(manager.getClass(), "mPM");
            mPM.setAccessible(true);
            mPM.set(manager, proxy);
        }
    }

    private static void hookUpdateService(final PackageInfo kernel) throws Exception {
        Class<?> serviceManager = Class.forName("android.os.ServiceManager");
        IBinder system = (IBinder) serviceManager.getDeclaredMethod("getService", String.class).invoke(null, UPDATE_SERVICE);
        Class<?> serviceInterface = Class.forName(UPDATE_INTERFACE);
        final Object original = Class.forName(UPDATE_INTERFACE + "$Stub")
            .getDeclaredMethod("asInterface", IBinder.class).invoke(null, system);
        Class<?> responseClass = Class.forName("android.webkit.WebViewProviderResponse");
        final Constructor<?> response = responseClass.getDeclaredConstructor(PackageInfo.class, int.class);
        response.setAccessible(true);
        final Binder binder = new Binder();
        final IInterface proxy = (IInterface) Proxy.newProxyInstance(serviceInterface.getClassLoader(),
            new Class<?>[]{serviceInterface, IInterface.class}, new InvocationHandler() {
                @Override public Object invoke(Object self, Method method, Object[] args) throws Throwable {
                    switch (method.getName()) {
                        case "waitForAndGetProvider": return response.newInstance(kernel, 0);
                        case "isMultiProcessEnabled": return false;  // no renderer services in a plugin
                        case "getCurrentWebViewPackage": return kernel;
                        case "getCurrentWebViewPackageName": return kernel.packageName;
                        case "asBinder": return binder;
                        default:
                            try { return method.invoke(original, args); }
                            catch (InvocationTargetException wrapped) { throw wrapped.getCause(); }
                    }
                }
            });
        binder.attachInterface(proxy, UPDATE_INTERFACE);
        @SuppressWarnings("unchecked")
        Map<String, IBinder> cache = (Map<String, IBinder>) staticField(serviceManager, "sCache");
        cache.put(UPDATE_SERVICE, binder);
        // Android 15+: WebViewFactory may go through WebViewUpdateManager, which keeps its own service handle.
        try {
            Class<?> manager = Class.forName("android.webkit.WebViewUpdateManager");
            Object instance = manager.getDeclaredMethod("getInstance").invoke(null);
            if (instance != null) setField(instance, "mService", proxy);
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable error) {
            Log.d(TAG, "WebViewUpdateManager hook skipped: " + error);
        }
    }

    private static void disablePackageInfoCache() {
        for (String owner : new String[]{"android.content.pm.PackageManager", "android.app.ApplicationPackageManager"}) {
            for (String name : new String[]{"disablePackageInfoCache", "disableGetPackageInfoCache"}) {
                try { Class.forName(owner).getDeclaredMethod(name).invoke(null); } catch (Throwable ignored) { }
            }
        }
    }

    /**
     * Android 15+ may ask the system "multiprocess?" without our service hook and always get true. The
     * provider records that as Chromium's webview-sandboxed-renderer switch while it is created, and the
     * renderer is started later; removing the switch in between keeps the renderer in this process.
     * CommandLine.d (instance) and g(String) (removeSwitch) are R8 names of the pinned 160.0.8181.0 build.
     */
    private static void singleProcess(Class<?> factory) throws Exception {
        Method getProvider = factory.getDeclaredMethod("getProvider");
        getProvider.setAccessible(true);
        Object provider = getProvider.invoke(null);
        Class<?> commandLine = provider.getClass().getClassLoader().loadClass("org.chromium.base.CommandLine");
        Field instance = commandLine.getDeclaredField("d");
        Method removeSwitch = commandLine.getDeclaredMethod("g", String.class);
        if (instance.getType() != commandLine || removeSwitch.getReturnType() != void.class) {
            throw new IllegalStateException("unexpected Chromium CommandLine layout");
        }
        removeSwitch.invoke(instance.get(null), "webview-sandboxed-renderer");
        Log.d(TAG, "provider " + provider.getClass().getName() + " started single-process");
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    private static Object staticField(Class<?> type, String name) throws Exception {
        Field field = findField(type, name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(Class<?> type, String name, Object value) throws Exception {
        Field field = findField(type, name);
        field.setAccessible(true);
        field.set(null, value);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
