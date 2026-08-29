package ai.aibridge;

import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Tiny thread-safe log ring buffer. The HTTP server and service push lines
 * here; the Server dashboard polls/observes it. Keeps last 200 lines.
 */
public class LogBus {

    public interface Listener {
        void onLine(String line);
    }

    private final ConcurrentLinkedQueue<String> lines = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Listener> listeners = new ConcurrentLinkedQueue<>();
    private static final int MAX = 200;

    public void log(String line) {
        String stamped = "[" + Thread.currentThread().getName() + "] " + line;
        lines.add(stamped);
        while (lines.size() > MAX) lines.poll();
        for (Listener l : listeners) l.onLine(stamped);
    }

    public String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : lines) {
            sb.append(s).append("\n");
        }
        return sb.toString();
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static LogBus instance = new LogBus();
    public static LogBus get() { return instance; }
}
