import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Load test for the shortener: creates N short links, then hammers redirects (90%) and creates (10%)
 * from many threads for a fixed duration and prints throughput and latency percentiles.
 *
 * Run: java loadtest/LoadTest.java [baseUrl] [threads] [seconds]
 */
public class LoadTest {

    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://localhost:8080";
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 30;

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(Executors.newFixedThreadPool(threads))
                .build();

        System.out.printf("Seeding 1000 links at %s ...%n", base);
        List<String> codes = new ArrayList<>();
        Pattern codeRe = Pattern.compile("\"code\"\\s*:\\s*\"([^\"]+)\"");
        for (int i = 0; i < 1000; i++) {
            HttpResponse<String> r = client.send(create(base, i), HttpResponse.BodyHandlers.ofString());
            Matcher m = codeRe.matcher(r.body());
            if (m.find()) {
                codes.add(m.group(1));
            }
        }

        System.out.printf("Running %d threads for %ds (90%% redirects / 10%% creates) ...%n", threads, seconds);
        long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        long[][] samples = new long[threads][];
        int[] counts = new int[threads];
        AtomicLong errors = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            futures.add(pool.submit(() -> {
                long[] lat = new long[2_000_000];
                int n = 0;
                ThreadLocalRandom rnd = ThreadLocalRandom.current();
                while (System.nanoTime() < end && n < lat.length) {
                    boolean redirect = rnd.nextInt(10) != 0;
                    HttpRequest req = redirect
                            ? HttpRequest.newBuilder(URI.create(base + "/" + codes.get(rnd.nextInt(codes.size())))).GET().build()
                            : create(base, rnd.nextInt());
                    long start = System.nanoTime();
                    try {
                        HttpResponse<Void> r = client.send(req, HttpResponse.BodyHandlers.discarding());
                        int expected = redirect ? 302 : 201;
                        if (r.statusCode() != expected) {
                            errors.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                    lat[n++] = System.nanoTime() - start;
                }
                samples[id] = lat;
                counts[id] = n;
            }));
        }
        for (var f : futures) {
            f.get();
        }
        pool.shutdown();

        int total = Arrays.stream(counts).sum();
        long[] all = new long[total];
        int off = 0;
        for (int t = 0; t < threads; t++) {
            System.arraycopy(samples[t], 0, all, off, counts[t]);
            off += counts[t];
        }
        Arrays.sort(all);
        System.out.println("----------------------------------------");
        System.out.printf("Requests     : %,d%n", total);
        System.out.printf("Errors       : %,d%n", errors.get());
        System.out.printf("Throughput   : %,.0f req/s%n", total / (double) seconds);
        System.out.printf("Latency p50  : %.2f ms%n", pct(all, 0.50));
        System.out.printf("Latency p95  : %.2f ms%n", pct(all, 0.95));
        System.out.printf("Latency p99  : %.2f ms%n", pct(all, 0.99));
        System.out.printf("Latency max  : %.2f ms%n", all[all.length - 1] / 1e6);
        System.exit(0);
    }

    private static HttpRequest create(String base, int i) {
        return HttpRequest.newBuilder(URI.create(base + "/api/v1/urls"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"url\":\"https://example.com/load/" + i + "\"}"))
                .build();
    }

    private static double pct(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) (sorted.length * p))] / 1e6;
    }
}
