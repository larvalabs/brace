package benchmark.jmh;

import benchmark.model.Fortune;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larvalabs.brace.Json;
import gg.jte.ContentType;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import gg.jte.output.Utf8ByteOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import org.openjdk.jmh.annotations.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * M6 allocation benchmark (gap #3): renders the same page two ways and reports
 * {@code gc.alloc.rate.norm} (bytes allocated per render), which wrk cannot see.
 *
 * <p>The two paths are the exact before/after of M6, isolated to the rendering unit:
 * <ul>
 *   <li><b>pre</b> — a jte engine without {@code binaryStaticContent}, rendered into a
 *       {@code StringOutput}, then {@code toString()} then {@code getBytes(UTF_8)} — the
 *       three-pass materialization the finding describes (StringBuilder char[] → String → byte[]).</li>
 *   <li><b>post</b> — a jte engine with {@code binaryStaticContent(true)}, rendered straight into a
 *       {@code Utf8ByteOutput}, then {@code toByteArray()} — what {@code TemplateEngine.renderToBytes}
 *       now does: static chunks are pre-encoded byte[], only dynamic values are encoded, one final array.</li>
 * </ul>
 * The JSON pair mirrors {@code Json.of}: {@code writeValueAsString().getBytes()} vs {@code writeValueAsBytes()}.
 *
 * <p>Run from the repo root (the template path is repo-root-relative, matching {@code App.java}):
 * {@code java --enable-preview -cp benchmark/target/brace-benchmark-*.jar benchmark.jmh.JmhRunner RenderAllocBench}
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(value = 1, jvmArgsAppend = "--enable-preview")
public class RenderAllocBench {

    /** Row count drives how much static template content repeats; the M6 win scales with it. 12 ≈ the
     *  TFB Fortunes size, 100 ≈ a real list page. */
    @Param({"12", "100"})
    public int rows;

    private TemplateEngine engineString; // pre-M6: String static content
    private TemplateEngine engineBytes;  // post-M6: binaryStaticContent
    private Map<String, Object> params;
    private ObjectMapper mapper;
    private List<FortuneDto> jsonValue;

    /** A plain DTO for the JSON path — avoids Json.of()'s entity-leak warning and is representative of
     *  what handlers actually serialize (records/DTOs, never entities). */
    public record FortuneDto(int id, String message) {}

    @Setup
    public void setup() {
        String viewsDir = System.getProperty("bench.views", "benchmark/src/main/resources/views");
        var resolver = new DirectoryCodeResolver(Path.of(viewsDir));

        engineString = TemplateEngine.create(resolver, ContentType.Html);
        // binaryStaticContent stays default (false): static chunks are String constants → StringOutput.

        engineBytes = TemplateEngine.create(resolver, ContentType.Html);
        engineBytes.setBinaryStaticContent(true); // static chunks become pre-encoded byte[] (M6).

        var fortunes = new ArrayList<Fortune>(rows);
        jsonValue = new ArrayList<>(rows);
        for (int i = 1; i <= rows; i++) {
            var f = new Fortune();
            f.id = i;
            f.message = "Fortune number " + i + " — a line of representative content, some <html> & text.";
            fortunes.add(f);
            jsonValue.add(new FortuneDto(f.id, f.message));
        }
        params = new HashMap<>();
        params.put("fortunes", fortunes);

        mapper = Json.mapper();
    }

    @Benchmark
    public byte[] view_pre_stringThenBytes() {
        var out = new StringOutput();
        engineString.render("fortunes.jte", params, out);
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public byte[] view_post_bytes() {
        var out = new Utf8ByteOutput();
        engineBytes.render("fortunes.jte", params, out);
        return out.toByteArray();
    }

    @Benchmark
    public byte[] json_pre_stringThenBytes() throws Exception {
        return mapper.writeValueAsString(jsonValue).getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public byte[] json_post_bytes() throws Exception {
        return mapper.writeValueAsBytes(jsonValue);
    }
}
