package bench.commerce;

@FunctionalInterface
public interface TimeSource {
    long nowMillis();
}
