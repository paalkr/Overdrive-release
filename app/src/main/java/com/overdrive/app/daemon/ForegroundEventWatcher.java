package com.overdrive.app.daemon;

import com.overdrive.app.logging.DaemonLogger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * Publishes what is in the foreground, read from the system event log.
 *
 * <p><b>Why the event log.</b> The home panel's original foreground signal was the accessibility
 * service, and everything wrong with it came from that: the service has to be bound before the
 * panel can work at all (measured 2026-09-12: ~110s unbound after ACC-on, panel absent the whole
 * time), and its events arrive late. Timed on the car against the same two transitions:
 *
 * <pre>
 *   leaving home   wm_task_moved      23:36:57.432   accessibility path reacted 23:36:58.121
 *   returning home am_resume_activity 23:37:01.374   accessibility path reacted 23:37:01.877
 * </pre>
 *
 * The system knew 0.5-0.7s earlier, every time. {@code am_resume_activity} also carries the exact
 * {@code package/activity}, which is the precision the panel needs — the launcher's home screen
 * and its app drawer are separate activities in one package, so a package-level answer calls the
 * drawer "home" and draws the panel over it.
 *
 * <p><b>Why the daemon.</b> Reading the event log needs READ_LOGS, which the app process does not
 * have and the daemon (shell, UID 2000) does. No hidden APIs and no permissions beyond what the
 * daemon already runs with.
 *
 * <p><b>Two signals, not one.</b> {@code am_resume_activity} is authoritative but, when launching
 * a cold app, only arrives once that app resumes — 780ms after the launch began in the measurement
 * above. {@code wm_task_moved}/{@code wm_task_created} fire essentially at launch start. So this
 * publishes a cheap {@code PENDING} line the instant a task comes forward, and the resolved
 * component when it is known. The panel uses PENDING only to hide (see its consumer): while the
 * panel is attached the launcher is by definition already in front, so any OTHER task coming
 * forward means something is about to cover home — safe to act on immediately, and it cannot
 * cause a flicker on the way back because a task moving forward while we are detached is left for
 * the authoritative line to resolve.
 */
public final class ForegroundEventWatcher {

    private static final String TAG = "ForegroundEventWatcher";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);

    /** Lives in the app's own external files dir so the app process can read it without any
     *  permission dance — the same route the telemetry snapshot already uses. */
    public static final String STATE_FILE =
            "/storage/emulated/0/Android/data/com.overdrive.app/files/foreground.txt";

    private static volatile Thread worker;
    private static volatile boolean running;
    private static volatile String lastWritten = "";

    private ForegroundEventWatcher() {}

    public static synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        running = true;
        worker = new Thread(ForegroundEventWatcher::run, "foreground-event-watcher");
        worker.setDaemon(true);
        worker.start();
        logger.info("Foreground event watcher started (event log -> " + STATE_FILE + ")");
    }

    public static synchronized void stop() {
        running = false;
        Thread t = worker;
        worker = null;
        if (t != null) t.interrupt();
    }

    private static void run() {
        // Seed before streaming. The event log only speaks on CHANGE, so on a fresh start — which
        // is every ACC-on, since the app process is restarted — nothing has been published yet and
        // the file still holds whatever the previous process left, possibly a stale component or a
        // bare PENDING. If the car then comes up on the home screen and the user touches nothing,
        // no event ever fires and the panel waits indefinitely for an answer that is not coming.
        publishCurrentForeground();

        while (running) {
            Process p = null;
            try {
                // -s filters to these tags in the binary event buffer, so this stream is a
                // handful of lines per app switch rather than the whole firehose.
                // NOT -v raw: that prints only the record payload, with no tag, so there is no
                // way to tell an am_resume_activity line from a wm_task_moved one. -v time keeps
                // the "I/tag(pid): [...]" shape the parser below matches on.
                p = new ProcessBuilder("sh", "-c",
                        "logcat -b events -v time -s am_resume_activity wm_task_moved wm_task_created")
                        .redirectErrorStream(true).start();
                BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line;
                while (running && (line = r.readLine()) != null) {
                    handleLine(line);
                }
            } catch (Throwable t) {
                if (running) logger.warn("event log reader died: " + t.getMessage());
            } finally {
                if (p != null) { try { p.destroyForcibly(); } catch (Throwable ignored) {} }
            }
            if (!running) return;
            // logcat can be killed by a buffer wipe or a low-memory sweep; come back rather than
            // leaving the panel without its primary signal for the rest of the drive.
            try { Thread.sleep(2000); } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Ask the window manager what is in front right now and publish it, so the panel has an answer
     * before the first app switch.
     *
     * <p>No task id is available from this source, which is fine: the id only matters for
     * suppressing a PENDING, and this never publishes one.
     */
    private static void publishCurrentForeground() {
        Process p = null;
        try {
            p = new ProcessBuilder("sh", "-c",
                    "dumpsys window 2>/dev/null | grep -m1 mCurrentFocus")
                    .redirectErrorStream(true).start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = r.readLine();
            p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (line == null) return;
            // mCurrentFocus=Window{hash u0 pkg/activity}
            int brace = line.lastIndexOf('{');
            if (brace < 0) return;
            String inner = line.substring(brace + 1).replace("}", "").trim();
            String[] parts = inner.split("\\s+");
            if (parts.length == 0) return;
            String comp = parts[parts.length - 1];
            if (!comp.contains("/")) return;
            logger.info("Seeding foreground from window manager: " + comp);
            publish(expandComponent(comp));
        } catch (Throwable t) {
            logger.warn("foreground seed failed: " + t.getMessage());
        } finally {
            if (p != null) { try { p.destroyForcibly(); } catch (Throwable ignored) {} }
        }
    }

    private static void handleLine(String line) {
        if (line == null || line.isEmpty()) return;
        if (line.contains("am_resume_activity")) {
            // [0,53473737,86,com.android.launcher3/.home.MainActivity]
            //  ^user ^hash   ^task ^component
            String comp = lastBracketField(line);
            String task = bracketField(line, 2);
            if (comp != null && comp.contains("/")) {
                publish(expandComponent(comp) + (task != null ? "@" + task : ""));
            }
        } else if (line.contains("wm_task_moved") || line.contains("wm_task_created")) {
            // [89,0,2147483647] — the task is the first field. Published so the consumer can
            // tell "another app is coming forward" from "the launcher's own task moved", which
            // it cannot do from the bare edge: on 2026-09-13 the launcher's task moving while
            // the panel was up made it hide and immediately re-show, dozens of times a second
            // during a relaunch.
            String task = bracketField(line, 0);
            publish("PENDING" + (task != null ? "@" + task : ""));
        }
    }

    /** Zero-indexed comma-separated field inside the trailing [...] record. */
    private static String bracketField(String line, int index) {
        int open = line.lastIndexOf('[');
        int close = line.lastIndexOf(']');
        if (open < 0 || close <= open) return null;
        String[] parts = line.substring(open + 1, close).split(",");
        if (index < 0 || index >= parts.length) return null;
        String v = parts[index].trim();
        return v.isEmpty() ? null : v;
    }

    /** Last comma-separated field inside the trailing [...] record. */
    private static String lastBracketField(String line) {
        int open = line.lastIndexOf('[');
        int close = line.lastIndexOf(']');
        if (open < 0 || close <= open) return null;
        String inner = line.substring(open + 1, close);
        int comma = inner.lastIndexOf(',');
        String field = (comma >= 0) ? inner.substring(comma + 1) : inner;
        return field.trim();
    }

    /**
     * The event log abbreviates the activity when it shares the package prefix
     * ({@code com.android.launcher3/.home.MainActivity}); the panel compares against the full
     * component, so expand it here rather than making every consumer handle both spellings.
     */
    private static String expandComponent(String comp) {
        int slash = comp.indexOf('/');
        if (slash < 0) return comp;
        String pkg = comp.substring(0, slash);
        String act = comp.substring(slash + 1);
        if (act.startsWith(".")) act = pkg + act;
        return pkg + "/" + act;
    }

    private static void publish(String value) {
        // PENDING is an edge, not a state: repeat it so a second app switch still fires, but
        // never rewrite the same resolved component twice — the consumer watches for file
        // changes and a no-op write would wake it for nothing.
        if (!value.startsWith("PENDING") && value.equals(lastWritten)) return;
        lastWritten = value;
        try {
            File f = new File(STATE_FILE);
            File dir = f.getParentFile();
            if (dir != null && !dir.exists()) dir.mkdirs();
            // Truncating write: the consumer reads the whole (tiny) file, so a partial read can
            // only ever yield a short string, which it treats as unknown.
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write((value + "\n").getBytes("UTF-8"));
                out.flush();
            }
        } catch (Throwable t) {
            logger.warn("foreground publish failed: " + t.getMessage());
        }
    }
}
