package bench.queue;

/** Injectable wall-clock source so queue behavior can be tested without sleeping. */
@FunctionalInterface
public interface TimeSource {
    long nowMillis();

    static TimeSource system() {
        return System::currentTimeMillis;
    }
}
