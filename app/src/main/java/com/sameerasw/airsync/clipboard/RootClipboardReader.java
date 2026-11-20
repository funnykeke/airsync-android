package com.sameerasw.airsync.clipboard;

import android.content.AttributionSource;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Base64;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/** A network-free child process, launched as shell only after the user opts in to Root. */
public final class RootClipboardReader {
    private static final int MAX_BYTES = 65536;

    private static void invalidate() {
        System.out.println("INVALIDATE");
        System.out.flush();
    }

    private static void field(Context context, String name, Object value) throws Exception {
        Field field = context.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(context, value);
    }

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 2000) throw new SecurityException();
            Looper.prepareMainLooper();
            Class<?> type = Class.forName("android.app.ActivityThread");
            Object thread = type.getMethod("systemMain").invoke(null);
            Context system = (Context) type.getMethod("getSystemContext").invoke(thread);
            Context context = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
            // app_process starts with an android attribution package. Correct only
            // this process's Context to its real UID/package, never system policy.
            field(context, "mOpPackageName", "com.android.shell");
            if (Build.VERSION.SDK_INT >= 31) {
                field(context, "mAttributionSource", new AttributionSource.Builder(Process.myUid())
                        .setPackageName("com.android.shell").build());
            }
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            ClipboardManager.OnPrimaryClipChangedListener listener = () -> {
                try {
                    ClipData clip = clipboard.getPrimaryClip();
                    if (clip == null || clip.getItemCount() == 0) { invalidate(); return; }
                    ClipDescription description = clip.getDescription();
                    if (description != null && description.getExtras() != null &&
                            description.getExtras().getBoolean("android.content.extra.IS_SENSITIVE", false)) { invalidate(); return; }
                    // Never coerce a URI/Intent to text or load content providers.
                    CharSequence value = clip.getItemAt(0).getText();
                    if (value == null || value.length() > MAX_BYTES) { invalidate(); return; }
                    byte[] bytes = value.toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length == 0 || bytes.length > MAX_BYTES) { invalidate(); return; }
                    System.out.println("TEXT:" + Base64.encodeToString(bytes, Base64.NO_WRAP));
                    System.out.flush();
                    if (System.out.checkError()) System.exit(0);
                } catch (Throwable error) {
                    System.out.println("ERROR:READ");
                    System.out.flush();
                }
            };
            clipboard.addPrimaryClipChangedListener(listener);
            Handler main = new Handler(Looper.getMainLooper());
            // EOF is tied to the owning AirSync process. No timers, polling or wakelocks.
            Thread lifetime = new Thread(() -> {
                try { while (System.in.read() != -1) { /* owner has no commands */ } }
                catch (Exception ignored) { }
                main.post(() -> {
                    clipboard.removePrimaryClipChangedListener(listener);
                    System.exit(0);
                });
            }, "AirSync-owner");
            lifetime.setDaemon(true);
            lifetime.start();
            System.out.println("READY");
            System.out.flush();
            Looper.loop();
        } catch (Throwable error) {
            // No exception text: framework errors can include private clipboard data.
            System.out.println("ERROR:BOOTSTRAP");
            System.out.flush();
            System.exit(1);
        }
    }
}
